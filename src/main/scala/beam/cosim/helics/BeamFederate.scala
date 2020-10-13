package beam.cosim.helics

import beam.agentsim.scheduler.Trigger
import beam.sim.BeamServices
import com.github.beam.HelicsLoader
import com.java.helics._
import com.java.helics.helicsJNI._
import com.typesafe.scalalogging.StrictLogging
import spray.json.DefaultJsonProtocol.{JsValueFormat, StringJsonFormat, listFormat, mapFormat}
import spray.json.{JsNumber, JsString, JsValue, _}

object BeamFederate {
  // Lazy makes sure that it is initialized only once
  lazy val loadHelics: Unit = {
    HelicsLoader.load()
  }

  lazy val unloadHelics: Unit = this.synchronized {
    helics.helicsCleanupLibrary()
    helics.helicsCloseLibrary()
  }

  case class BeamFederateTrigger(tick: Int) extends Trigger

  implicit object AnyJsonFormat extends JsonFormat[Any] {
    def write(x: Any): JsValue = x match {
      case n: Int => JsNumber(n)
      case b: Boolean if b => JsTrue
      case b: Boolean if !b => JsFalse
      case s => JsString(s.toString)
    }
    def read(value: JsValue): Any = value match {
      case JsNumber(n) => n.intValue()
      case JsTrue => true
      case JsFalse => false
      case s => s.asInstanceOf[String]
    }
  }

  implicit object MapAnyJsonFormat extends JsonFormat[Map[String, Any]] {
    def write(c: Map[String, Any]): JsValue = {
      c.map {
        case (k, v) => k -> v.toJson
      }.toJson
    }
    def read(value: JsValue): Map[String, Any] = {
      value.convertTo[Map[String, JsValue]].map(x => x._1 -> x._2.toJson)
    }
  }

  implicit object ListMapAnyJsonFormat extends JsonFormat[List[Map[String, Any]]] {
    def write(c: List[Map[String, Any]]): JsValue = {
      c.toJson
    }
    def read(value: JsValue): List[Map[String, Any]] = {
      value.convertTo[List[Map[String, Any]]]
    }
  }

}

case class BeamFederate(beamServices: BeamServices, fedName: String, fedTimeStep: Int, bufferSize: Int, dataOutStreamPoint: String, dataInStreamPoint: String) extends StrictLogging {
  import BeamFederate._
  private var dataOutStreamHandle: Option[SWIGTYPE_p_void] = None
  private var dataInStreamHandle: Option[SWIGTYPE_p_void] = None
  private val fedInfo = helics.helicsCreateFederateInfo()
  helics.helicsFederateInfoSetCoreName(fedInfo, fedName)
  helics.helicsFederateInfoSetCoreTypeFromString(fedInfo, "zmq")
  helics.helicsFederateInfoSetCoreInitString(fedInfo, "--federates=1")
  helics.helicsFederateInfoSetTimeProperty(fedInfo, helics_property_time_delta_get(), 1.0)
  helics.helicsFederateInfoSetIntegerProperty(fedInfo, helics_property_int_log_level_get(), 1)
  logger.debug(s"FederateInfo created")
  private val fedComb = helics.helicsCreateCombinationFederate(fedName, fedInfo)
  logger.debug(s"CombinationFederate created")
  // ******
  // register new BEAM publications here
  registerPublication(dataOutStreamPoint)
  // ******
  // register new BEAM subscriptions here
  registerSubscription(dataInStreamPoint)
  // ******
  helics.helicsFederateEnterInitializingMode(fedComb)
  logger.debug(s"Federate initialized and wait for Executing Mode to be granted")
  helics.helicsFederateEnterExecutingMode(fedComb)
  logger.debug(s"Federate successfully entered the Executing Mode")

  def publish(labeledData: List[Map[String, Any]]): Unit = {
    dataOutStreamHandle.foreach(helics.helicsFederatePublishJSON(_, labeledData.toJson.compactPrint))
    logger.debug("Data published into HELICS")
  }

  def syncAndMoveToNextTimeStep(time: Int): List[Map[String, Any]] = {
    var currentTime = -1.0
    logger.debug(s"requesting the time $time from the broker")
    while (currentTime < time) currentTime = helics.helicsFederateRequestTime(fedComb, time)
    logger.debug(s"the time $time granted was $currentTime")
    dataInStreamHandle.map {
      handle =>
        val buffer = new Array[Byte](bufferSize)
        val bufferInt = new Array[Int](1)
        helics.helicsInputGetString(handle, buffer, bufferInt)
        val value = buffer.take(bufferInt(0)).map(_.toChar).mkString.parseJson
        logger.debug("Received physical bounds from the grid")
        value.convertTo[List[Map[String, Any]]]
    }.getOrElse(List.empty[Map[String, Any]])
  }

  private def registerPublication(pubName: String): Unit = {
    dataOutStreamHandle = Some(helics.helicsFederateRegisterPublication(fedComb, fedName+"/"+pubName, helics_data_type.helics_data_type_string, ""))
    //helics.helicsFederateRegisterFromPublicationJSON(fedComb, fedName+"/"+pubName)
    logger.debug(s"registering $pubName to CombinationFederate")
  }

  private def registerSubscription(subName: String): Unit = {
    dataInStreamHandle = Some(helics.helicsFederateRegisterSubscription(fedComb, subName, ""))
    logger.debug(s"registering $subName to CombinationFederate")
  }

  def close(): Unit = {
    if (helics.helicsFederateIsValid(fedComb) == 1) {
      helics.helicsFederateFinalize(fedComb)
      helics.helicsFederateFree(fedComb)
      helics.helicsCloseLibrary()
      logger.debug(s"closing BeamFederate")
    } else {
      logger.error(s"helics federate is not valid!")
    }
  }
}
