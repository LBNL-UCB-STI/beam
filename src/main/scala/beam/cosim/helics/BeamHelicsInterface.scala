package beam.cosim.helics

import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.scheduler.Trigger
import com.github.beam.HelicsLoader
import com.java.helics._
import com.java.helics.helicsJNI._
import com.typesafe.scalalogging.StrictLogging
import spray.json.DefaultJsonProtocol.{listFormat, mapFormat, JsValueFormat, StringJsonFormat}
import spray.json.{JsNumber, JsString, JsValue, _}
import org.matsim.api.core.v01.Id

object BeamHelicsInterface {
  // Lazy makes sure that it is initialized only once
  lazy val loadHelicsIfNotAlreadyLoaded: Unit = HelicsLoader.load()

  def unloadHelics(): Unit = this.synchronized {
    helics.helicsCleanupLibrary()
    helics.helicsCloseLibrary()
  }

  /**
    * Create a Federate Instance
    * @param fedName FEDERATE_NAME
    * @param bufferSize BUFFER_SIZE
    * @param dataOutStreamPointMaybe "PUBLICATION_NAME"
    * @param dataInStreamPointMaybe "FEDERATE_NAME/SUBSCRIPTION_NAME"
    * @return
    */
  def getFederate(
    fedName: String,
    coreType: String,
    coreInitString: String,
    timeDeltaProperty: Double,
    intLogLevel: Int,
    bufferSize: Int,
    dataOutStreamPointMaybe: Option[String] = None,
    dataInStreamPointMaybe: Option[String] = None
  ): BeamFederate = {
    loadHelicsIfNotAlreadyLoaded
    BeamFederate(
      fedName,
      coreType,
      coreInitString,
      timeDeltaProperty,
      intLogLevel,
      bufferSize,
      dataOutStreamPointMaybe,
      dataInStreamPointMaybe
    )
  }

  /**
    * Create a Broker instance and a Federate Instance
    * @param brokerName BROKER_NAME
    * @param numFederates number of federates to consider
    * @param fedName FEDERATE_NAME
    * @param bufferSize BUFFER_SIZE
    * @param dataOutStreamPointMaybe PUBLICATION_NAME
    * @param dataInStreamPointMaybe FEDERATE_NAME/SUBSCRIPTION_NAME
    * @return
    */
  def getBroker(
    brokerName: String,
    numFederates: Int,
    fedName: String,
    coreType: String,
    coreInitString: String,
    timeDeltaProperty: Double,
    intLogLevel: Int,
    bufferSize: Int,
    dataOutStreamPointMaybe: Option[String] = None,
    dataInStreamPointMaybe: Option[String] = None
  ): BeamBroker = {
    loadHelicsIfNotAlreadyLoaded
    BeamBroker(
      brokerName,
      numFederates,
      fedName,
      coreType,
      coreInitString,
      timeDeltaProperty,
      intLogLevel,
      bufferSize,
      dataOutStreamPointMaybe,
      dataInStreamPointMaybe
    )
  }

  case class BeamFederateTrigger(tick: Int) extends Trigger

  implicit object AnyJsonFormat extends JsonFormat[Any] {

    def write(x: Any): JsValue = x match {
      case rf: ReservedFor  => JsString(rf.toString)
      case n: Double        => JsNumber(n)
      case n: Int           => JsNumber(n)
      case b: Boolean if b  => JsTrue
      case b: Boolean if !b => JsFalse
      case s: String        => JsString(s)
      case id: Id[_]        => JsString(id.toString)
    }

    def read(value: JsValue): Any = value match {
      case JsNumber(n) if n.isInstanceOf[Int] => n.intValue()
      case JsNumber(n)                        => n.doubleValue()
      case JsTrue                             => true
      case JsFalse                            => false
      case JsString(s)                        => s
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

  case class BeamFederate(
    fedName: String,
    coreType: String,
    coreInitString: String,
    timeDeltaProperty: Double,
    intLogLevel: Int,
    bufferSize: Int,
    dataOutStreamPointMaybe: Option[String] = None,
    dataInStreamPointMaybe: Option[String] = None
  ) extends StrictLogging {
    private var dataOutStreamHandle: Option[SWIGTYPE_p_void] = None
    private var dataInStreamHandle: Option[SWIGTYPE_p_void] = None

    // **************************
    val fedInfo: SWIGTYPE_p_void = helics.helicsCreateFederateInfo()
    helics.helicsFederateInfoSetCoreName(fedInfo, fedName)
    helics.helicsFederateInfoSetCoreTypeFromString(fedInfo, coreType)
    helics.helicsFederateInfoSetCoreInitString(fedInfo, coreInitString)
    helics.helicsFederateInfoSetTimeProperty(fedInfo, helics_property_time_delta_get(), timeDeltaProperty)
    helics.helicsFederateInfoSetIntegerProperty(fedInfo, helics_property_int_log_level_get(), intLogLevel)
    logger.debug(s"FederateInfo created")
    val fedComb: SWIGTYPE_p_void = helics.helicsCreateCombinationFederate(fedName, fedInfo)
    logger.debug(s"CombinationFederate created")
    // ******
    // register new BEAM publications here
    dataOutStreamPointMaybe.foreach(registerPublication)
    // ******
    // register new BEAM subscriptions here
    dataInStreamPointMaybe.foreach(registerSubscription)
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
      * Collect message that has been published by other federates
      * @param time the requested time
      * @return raw message in string format
      */
    private def collectRaw(): String = {
      dataInStreamHandle
        .map { handle =>
          val buffer = new Array[Byte](bufferSize)
          val bufferInt = new Array[Int](1)
          helics.helicsInputGetString(handle, buffer, bufferInt)
          buffer.take(bufferInt(0)).map(_.toChar).mkString
        }
        .getOrElse("")
    }

    /**
      * Collect JSON messages that has been published by other federates
      * The message is expected to be JSON, if not this method will fail
      * @param time the requested time
      * @return Message in List of Maps format
      */
    def collectJSON(): List[Map[String, Any]] = {
      val message = collectRaw()
      if (message.nonEmpty) {
        logger.debug("Received JSON Data via HELICS")
        try {
          message.replace("\u0000", "").parseJson.convertTo[List[Map[String, Any]]]
        } catch {
          case _: Throwable => List.empty[Map[String, Any]]
        }
      } else List.empty[Map[String, Any]]
    }

    /**
      * Collect list of String messages that has been published by other federates
      * The message is expected to be list of string, if not this method will fail
      * @param time the requested time
      * @return message in list of Strings format
      */
    def collectAny(): List[Any] = {
      val message = collectRaw()
      if (message.nonEmpty) {
        logger.debug("Received JSON Data via HELICS")
        message.split(",").toList
      } else List.empty[Any]
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
      logger.debug(s"closing BeamFederate")
      if (helics.helicsFederateIsValid(fedComb) == 1) {
        helics.helicsFederateFinalize(fedComb)
        helics.helicsFederateFree(fedComb)
        helics.helicsCloseLibrary()
        logger.debug(s"BeamFederate closed")
      } else {
        logger.error(s"helics federate is not valid!")
      }
    }
  }

  case class BeamBroker(
    brokerName: String,
    numFederates: Int,
    fedName: String,
    coreType: String,
    coreInitString: String,
    timeDeltaProperty: Double,
    intLogLevel: Int,
    bufferSize: Int,
    dataOutStreamPointMaybe: Option[String] = None,
    dataInStreamPointMaybe: Option[String] = None
  ) extends StrictLogging {
    private val broker = helics.helicsCreateBroker(coreType, "", s"-f $numFederates --name=$brokerName")
    lazy val isConnected: Boolean = helics.helicsBrokerIsConnected(broker) > 0

    private val federate: Option[BeamFederate] = if (isConnected) {
      Some(
        getFederate(
          fedName,
          coreType,
          coreInitString,
          timeDeltaProperty,
          intLogLevel,
          bufferSize,
          dataOutStreamPointMaybe,
          dataInStreamPointMaybe
        )
      )
    } else {
      None
    }
    def getBrokersFederate: Option[BeamFederate] = federate
  }
}
