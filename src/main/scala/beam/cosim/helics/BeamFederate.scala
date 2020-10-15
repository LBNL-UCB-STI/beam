package beam.cosim.helics

import beam.agentsim.scheduler.Trigger
import com.github.beam.HelicsLoader
import com.java.helics._
import com.java.helics.helicsJNI._
import com.typesafe.scalalogging.StrictLogging
import spray.json.DefaultJsonProtocol.{listFormat, mapFormat, JsValueFormat, StringJsonFormat}
import spray.json.{JsNumber, JsString, JsValue, _}

object BeamFederate {
  // Lazy makes sure that it is initialized only once
  lazy val loadHelics: Unit = HelicsLoader.load()

  def unloadHelics(): Unit = this.synchronized {
    helics.helicsCleanupLibrary()
    helics.helicsCloseLibrary()
  }

  /**
    * Create a Federate Instance
    * @param fedName FEDERATE_NAME
    * @param dataOutStreamPointMaybe "PUBLICATION_NAME"
    * @param dataInStreamPointAndBufferSizeMaybe ("FEDERATE_NAME/SUBSCRIPTION_NAME" , "BUFFER_SIZE")
    * @return
    */
  def getFederateInstance(
    fedName: String,
    dataOutStreamPointMaybe: Option[String] = None,
    dataInStreamPointAndBufferSizeMaybe: Option[(String, Int)] = None
  ): BeamFederate = {
    loadHelics
    BeamFederate(fedName, dataOutStreamPointMaybe, dataInStreamPointAndBufferSizeMaybe)
  }

  /**
    * Create a Broker instance and a Federate Instance
    * @param brokerName BROKER_NAME
    * @param numFederates number of federates to consider
    * @param fedName FEDERATE_NAME
    * @param dataOutStreamPointMaybe PUBLICATION_NAME
    * @param dataInStreamPointAndBufferSizeMaybe (FEDERATE_NAME/SUBSCRIPTION_NAME , BUFFER_SIZE)
    * @return
    */
  def getBrokerInstance(
    brokerName: String,
    numFederates: Int,
    fedName: String,
    dataOutStreamPointMaybe: Option[String] = None,
    dataInStreamPointAndBufferSizeMaybe: Option[(String, Int)] = None
  ): BeamBroker = {
    loadHelics
    BeamBroker(brokerName, numFederates, fedName, dataOutStreamPointMaybe, dataInStreamPointAndBufferSizeMaybe)
  }

  case class BeamFederateTrigger(tick: Int) extends Trigger

  implicit object AnyJsonFormat extends JsonFormat[Any] {

    def write(x: Any): JsValue = x match {
      case n: Double        => JsNumber(n)
      case n: Int           => JsNumber(n)
      case b: Boolean if b  => JsTrue
      case b: Boolean if !b => JsFalse
      case s: String        => JsString(s)
    }

    def read(value: JsValue): Any = value match {
      case JsNumber(n) if n.isInstanceOf[Int] => n.intValue()
      case JsNumber(n)                        => n.doubleValue()
      case JsTrue                             => true
      case JsFalse                            => false
      case JsString(s)                        => s.asInstanceOf[String]
      case _                                  => deserializationError("JsNumber (Double or Int), JsTrue, JsFalse or JsString are expected")
    }
  }
  implicit object MapAnyJsonFormat extends JsonFormat[Map[String, Any]] {

    def write(c: Map[String, Any]): JsValue = {
      c.map { case (k, v) => k -> v.toJson(AnyJsonFormat) }.toJson
    }

    def read(value: JsValue): Map[String, Any] = value match {
      case JsObject(x) => x.map(y => y._1 -> y._2.convertTo[Any](AnyJsonFormat))
      case _           => deserializationError("JsObject expected")
    }
  }
  implicit object ListMapAnyJsonFormat extends JsonFormat[List[Map[String, Any]]] {

    def write(c: List[Map[String, Any]]): JsValue = {
      c.map(_.toJson(MapAnyJsonFormat)).toJson
    }

    def read(value: JsValue): List[Map[String, Any]] = value match {
      case JsArray(x) => x.map(_.convertTo[Map[String, Any]](MapAnyJsonFormat)).toList
      case _          => deserializationError("JsArray expected")
    }
  }

}

case class BeamFederate(
  fedName: String,
  dataOutStreamPointMaybe: Option[String] = None,
  dataInStreamPointAndBufferSizeMaybe: Option[(String, Int)] = None
) extends StrictLogging {
  import BeamFederate._
  private var dataOutStreamHandle: Option[SWIGTYPE_p_void] = None
  private var dataInStreamHandle: Option[SWIGTYPE_p_void] = None

  // **************************
  val fedInfo: SWIGTYPE_p_void = helics.helicsCreateFederateInfo()
  helics.helicsFederateInfoSetCoreName(fedInfo, fedName)
  helics.helicsFederateInfoSetCoreTypeFromString(fedInfo, "zmq")
  helics.helicsFederateInfoSetCoreInitString(fedInfo, "--federates=1")
  helics.helicsFederateInfoSetTimeProperty(fedInfo, helics_property_time_delta_get(), 1.0)
  helics.helicsFederateInfoSetIntegerProperty(fedInfo, helics_property_int_log_level_get(), 1)
  logger.debug(s"FederateInfo created")
  val fedComb: SWIGTYPE_p_void = helics.helicsCreateCombinationFederate(fedName, fedInfo)
  logger.debug(s"CombinationFederate created")
  // ******
  // register new BEAM publications here
  dataOutStreamPointMaybe.foreach(registerPublication)
  // ******
  // register new BEAM subscriptions here
  dataInStreamPointAndBufferSizeMaybe.foreach(x => registerSubscription(x._1))
  // ******
  helics.helicsFederateEnterInitializingMode(fedComb)
  logger.debug(s"Federate initialized and wait for Executing Mode to be granted")
  helics.helicsFederateEnterExecutingMode(fedComb)
  logger.debug(s"Federate successfully entered the Executing Mode")
  // **************************

  /**
    * Convert a list of Key Value Map into an array of JSON documents, then stringifies it before publishing via HELICS
    * @param labeledData List of Key -> Value
    */
  def publishJSON(labeledData: List[Map[String, Any]]): Unit = {
    dataOutStreamHandle.foreach(
      helics.helicsPublicationPublishString(_, labeledData.toJson(ListMapAnyJsonFormat).compactPrint.stripMargin)
    )
    logger.debug("Data published via HELICS")
  }

  /**
    * Convert a list of values into a string of element separated with a comma, then publishes it via HELICS
    * @param unlabeledData list of values or strings in form of "key:value"
    */
  def publish(unlabeledData: List[Any]): Unit = {
    dataOutStreamHandle.foreach(helics.helicsPublicationPublishString(_, unlabeledData.mkString(",")))
    logger.debug("Data published via HELICS: " + unlabeledData)
  }

  /**
    * Requests a co-simulation time and wait until it is awarded. The HELICS broker doesn't aware the requested time
    * until all the federates in co-simulation are synchronized
    * @param time the requested time
    * @return the awarded time
    */
  def sync(time: Int): Double = {
    var currentTime = -1.0
    logger.debug(s"requesting the time $time from the broker")
    while (currentTime < time) currentTime = helics.helicsFederateRequestTime(fedComb, time)
    logger.debug(s"the time $time granted was $currentTime")
    currentTime
  }

  /**
    * Call sync then collect message that has been published by other federates
    * @param time the requested time
    * @return (the awarded time, raw message in string format)
    */
  private def syncAndCollectRawData(time: Int): (Double, String) = {
    (
      sync(time),
      dataInStreamHandle
        .map { handle =>
          val buffer = new Array[Byte](dataInStreamPointAndBufferSizeMaybe.get._2)
          val bufferInt = new Array[Int](1)
          helics.helicsInputGetString(handle, buffer, bufferInt)
          buffer.take(bufferInt(0)).map(_.toChar).mkString
        }
        .getOrElse("")
    )
  }

  /**
    * Call sync then collect JSON messages that has been published by other federates
    * The message is expected to be JSON, if not this method will fail
    * @param time the requested time
    * @return (the awarded time, message in List of Maps format)
    */
  def syncAndCollectJSON(time: Int): (Double, List[Map[String, Any]]) = {
    val (currentTime, message) = syncAndCollectRawData(time)
    (currentTime, if (message.nonEmpty) {
      logger.debug("Received JSON Data via HELICS")
      message.replace("\u0000", "").parseJson.convertTo[List[Map[String, Any]]]
    } else {
      List.empty[Map[String, Any]]
    })
  }

  /**
    * Call sync then collect list of String messages that has been published by other federates
    * The message is expected to be list of string, if not this method will fail
    * @param time the requested time
    * @return (the awarded time, message in list of Strings format)
    */
  def syncAndCollect(time: Int): (Double, List[Any]) = {
    val (currentTime, message) = syncAndCollectRawData(time)
    (currentTime, if (message.nonEmpty) {
      logger.debug("Received JSON Data via HELICS")
      message.split(",").toList
    } else {
      List.empty[Any]
    })
  }

  /**
    * Register a publication channel
    * @param pubName the publication id which **is not expected** to be prefixed by a federate name, such as
    *                "PUBLICATION_NAME"
    */
  private def registerPublication(pubName: String): Unit = {
    dataOutStreamHandle = Some(
      helics.helicsFederateRegisterPublication(fedComb, pubName, helics_data_type.helics_data_type_string, "")
    )
    logger.debug(s"registering $pubName to CombinationFederate")
  }

  /**
    * Register a subscription channel
    * @param subName the subscription id which **is expected** to be prefixed by a federate name, such as
    *                "FEDERATE_NAME/SUBSCRIPTION_NAME"
    */
  private def registerSubscription(subName: String): Unit = {
    dataInStreamHandle = Some(helics.helicsFederateRegisterSubscription(fedComb, subName, ""))
    logger.debug(s"registering $subName to CombinationFederate")
  }

  /**
    * close HELICS library
    */
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

case class BeamBroker(
  brokerName: String,
  numFederates: Int,
  fedName: String,
  dataOutStreamPointMaybe: Option[String] = None,
  dataInStreamPointAndBufferSizeMaybe: Option[(String, Int)] = None
) extends StrictLogging {
  private val coreType: String = "zmq"
  private val broker = helics.helicsCreateBroker(coreType, "", s"-f $numFederates --name=$brokerName")
  lazy val isConnected: Boolean = helics.helicsBrokerIsConnected(broker) > 0
  private val federate: Option[BeamFederate] = if (isConnected) {
    Some(BeamFederate.getFederateInstance(fedName, dataOutStreamPointMaybe, dataInStreamPointAndBufferSizeMaybe))
  } else {
    None
  }
  def getFederate: Option[BeamFederate] = federate
}
