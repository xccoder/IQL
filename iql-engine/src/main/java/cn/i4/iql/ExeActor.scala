package cn.i4.iql

import java.io.{ByteArrayOutputStream, PrintStream}
import java.sql.Timestamp
import java.util.concurrent.ConcurrentHashMap

import akka.actor.{Actor, Props}
import cn.i4.iql.antlr.{IQLLexer, IQLListener, IQLParser}
import cn.i4.iql.domain.Bean._
import cn.i4.iql.utils.{BatchSQLRunnerEngine, ZkUtils}
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.{ANTLRInputStream, CommonTokenStream}
import org.apache.spark.sql.SparkSession
import cn.i4.iql.repl.SparkInterpreter
import com.alibaba.fastjson.{JSON, JSONArray, JSONObject}
import cn.i4.iql.IqlService._
import org.apache.spark.sql.bridge.SparkBridge


class ExeActor(spark: SparkSession,iqlSession:IQLSession) extends Actor with Logging {

  var sparkSession: SparkSession = _
  var interpreter: SparkInterpreter = _
  var resJson = new JSONObject()
  val zkValidActorPath = ZkUtils.validEnginePath + "/" + iqlSession.engineInfo + "_" + context.self.path.name

  override def preStart(): Unit = {
    warn("Actor Start ...")
    sparkSession = spark.newSession()
    interpreter = new SparkInterpreter(sparkSession)
    interpreter.start()
    Class.forName("cn.i4.iql.utils.SparkUDF").getMethods.filter(_.getModifiers == 9).foreach { f =>
      f.invoke(null, sparkSession)
    }
    ZkUtils.registerActorInEngine(ZkUtils.getZkClient(ZkUtils.ZKURL), zkValidActorPath, "", 6000, -1)
  }

  override def postStop(): Unit = {
    warn("Actor Stop ...")
    interpreter.close()
    sparkSession.stop()
  }

  override def receive: Receive = {

    case HiveCatalog() =>
        sender() ! getHiveCatalog

    case HiveCatalogWithAutoComplete() =>
      sender() ! getHiveCatalogWithAutoComplete

    case Iql(code, iql, variables) =>
      actorWapper(){() => {
        var rIql = iql
        val variblesIters = JSON.parseArray(variables).iterator()
        while (variblesIters.hasNext){
          val nObject = JSON.parseObject(variblesIters.next().toString)
          rIql = rIql.replace("${" + nObject.getString("name") + "}",nObject.getString("value"))
        }
        warn(rIql)
        schedulerMode = !schedulerMode  //切换调度池
        sparkSession.sparkContext.setLocalProperty("spark.scheduler.pool", if (schedulerMode) "pool_fair_1" else "pool_fair_2")
        resJson = new JSONObject()
        resJson.put("startTime", new Timestamp(System.currentTimeMillis))
        resJson.put("iql", iql)
        resJson.put("variables",variables)
        resJson.put("code", code)
        if (!code.trim.equals("")) interpreter.execute(code.replaceAll("\\/\\/[^\\n]*|\\/\\*([^\\*^\\/]*|[\\*^\\/*]*|[^\\**\\/]*)*\\*+\\/", "")) //过滤掉注释
        //为当前iql设置groupId
        val groupId = BatchSQLRunnerEngine.getGroupId
        resJson.put("engineInfoAndGroupId", iqlSession.engineInfo + "_" + groupId)
        sparkSession.sparkContext.clearJobGroup()
        sparkSession.sparkContext.setJobDescription("iql:" + rIql)
        sparkSession.sparkContext.setJobGroup("iqlid:" + groupId, "iql:" + rIql)
        //将该iql任务的唯一标识返回
        sender() ! resJson.toJSONString
        //解析iql并执行
        parse(rIql, new IQLSQLExecListener(sparkSession,iqlSession))
        }
      }

    case GetBatchResult(engineInfoAndGroupId) =>
        if (iqlSession.batchJob.keySet().contains(engineInfoAndGroupId)) {
          sender() ! iqlSession.batchJob.get(engineInfoAndGroupId)
          iqlSession.batchJob.remove(engineInfoAndGroupId)
        } else {
          sender() ! "{'status':'RUNNING'}"
        }

    case GetBatchResult(name) =>
      iqlSession.streamJob.get(name).isActive

    case CancelJob(groupId) =>
      sparkSession.sparkContext.cancelJobGroup("iqlid:" + groupId)
    case StopIQL() => context.system.terminate()

    case _ => None
  }

  //antlr4解析SQL语句
  def parse(input: String, listener: IQLSQLExecListener): Unit = {
    resJson.put("status", "FINISH")
    try {
      val loadLexer = new IQLLexer(new ANTLRInputStream(input))
      val tokens = new CommonTokenStream(loadLexer)
      val parser = new IQLParser(tokens)
      val stat = parser.statement()
      ParseTreeWalker.DEFAULT.walk(listener, stat)
      val endTime = System.currentTimeMillis()
      val take = (endTime - resJson.getTimestamp("startTime").getTime) / 1000
      resJson.put("hdfsPath", listener.getResult("hdfsPath"))
      resJson.put("schema", listener.getResult("schema"))
      resJson.put("takeTime", take)
      resJson.put("isSuccess", true)
    } catch {
      case e: Exception =>
        e.printStackTrace()
        resJson.put("isSuccess", false)
        val out = new ByteArrayOutputStream()
        e.printStackTrace(new PrintStream(out))
        resJson.put("errorMessage", new String(out.toByteArray))
        out.close()
    }
    iqlSession.batchJob.put(resJson.getString("engineInfoAndGroupId"), resJson.toJSONString)
  }

  //执行前从zk中删除当前对应节点（标记不可用），执行后往zk中写入可用节点（标记可以）
  def actorWapper()(f: () => Unit) {
    ZkUtils.deletePath(ZkUtils.getZkClient(ZkUtils.ZKURL), zkValidActorPath)
      try {
        f
      } catch {
        case e:Exception =>
          resJson.put("isSuccess", false)
          val out = new ByteArrayOutputStream()
          e.printStackTrace(new PrintStream(out))
          resJson.put("errorMessage", new String(out.toByteArray))
          sender() ! resJson.toJSONString
      }
    ZkUtils.registerActorInEngine(ZkUtils.getZkClient(ZkUtils.ZKURL), zkValidActorPath, "", 6000, -1)
  }

  //获取hive元数据信息
  def getHiveCatalog:String = {
    val hiveArray = new JSONArray()
    var num: Int = 0
    SparkBridge.getHiveCatalg(sparkSession).client.listDatabases("*").foreach(db => {
      num += 1
      val dbId = num
      val dbObj = new JSONObject()
      dbObj.put("id", dbId)
      dbObj.put("name",s"""<span class="button ico_close" style="background:url('iql/img/db.jpg') center center/15px 15px no-repeat"></span>$db""")
      dbObj.put("pId", 0)
      hiveArray.add(dbObj)
      SparkBridge.getHiveCatalg(sparkSession).client.listTables(db).foreach(tb => {
        num += 1
        val tbId = num
        val tbObj = new JSONObject()
        tbObj.put("id", tbId)
        tbObj.put("pId", dbId)
        tbObj.put("name",s"""<span class="button ico_close" style="background:url('iql/img/tb.jpg') center center/15px 15px no-repeat"></span>$tb""")
        hiveArray.add(tbObj)
        SparkBridge.getHiveCatalg(sparkSession).client.getTable(db, tb).schema.fields.foreach(f => {
          num += 1
          val fieldId = num
          val fieldObj = new JSONObject()
          fieldObj.put("id", fieldId)
          fieldObj.put("pId", tbId)
          fieldObj.put("name", f.name + "(" + f.dataType.typeName + ")")
          hiveArray.add(fieldObj)
        }
        )
      })
    })
    hiveArray.toJSONString
  }

  def getHiveCatalogWithAutoComplete:String = {
    val hiveObj = new JSONObject()
    SparkBridge.getHiveCatalg(sparkSession).client.listDatabases("*").foreach(db => {
      val tbArray = new JSONArray()
      SparkBridge.getHiveCatalg(sparkSession).client.listTables(db).foreach(tb => {
        tbArray.add(tb)
        val cloArray = new JSONArray()
        SparkBridge.getHiveCatalg(sparkSession).client.getTable(db, tb).schema.fields.foreach(f => cloArray.add(f.name))
        hiveObj.put(tb,cloArray)
      })
      hiveObj.put(db,tbArray)
    })
    hiveObj.toJSONString
  }
}

object ExeActor {

  def props(spark: SparkSession,iqlSession:IQLSession): Props = Props(new ExeActor(spark,iqlSession))

}
