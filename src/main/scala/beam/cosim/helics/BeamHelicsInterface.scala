package beam.cosim.helics

import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.infrastructure.ChargingNetwork.ChargingStation
import beam.agentsim.infrastructure.taz.TAZ
import beam.utils.FileUtils
import com.github.beam.HelicsLoader
import com.java.helics._
import com.java.helics.helicsJNI._
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Id
import spray.json.DefaultJsonProtocol.{JsValueFormat, StringJsonFormat, listFormat, mapFormat}
import spray.json.{JsNumber, JsString, JsValue, _}

import scala.concurrent._
import scala.concurrent.duration.{Duration, DurationInt}
import scala.util.control.NonFatal

object BeamHelicsInterface extends StrictLogging {
  // Lazy makes sure that it is initialized only once
  lazy val loadHelicsIfNotAlreadyLoaded: Unit = HelicsLoader.load()

  def messageToJsonString(labeledData: List[Map[String, Any]]): String = {
    labeledData.toJson(ListMapAnyJsonFormat).compactPrint.stripMargin
  }

  def closeHelics(): Unit = this.synchronized {
    try {
      helics.helicsCleanupLibrary()
      helics.helicsCloseLibrary()
      logger.info("Helics library cleaned and closed.")
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Cannot clean and unload helics library: ${ex.getMessage}")
    }
  }

  /**
    * Create a Federate Instance
    *
    * @param fedName                 FEDERATE_NAME
    * @param bufferSize              BUFFER_SIZE
    * @param dataOutStreamPointMaybe "PUBLICATION_NAME"
    * @param dataInStreamPointMaybe  "FEDERATE_NAME/SUBSCRIPTION_NAME"
    * @return
    */
  def getFederate(
    fedName: String,
    fedInfo: SWIGTYPE_p_void,
    bufferSize: Int,
    simulationStep: Int,
    dataOutStreamPointMaybe: Option[String] = None,
    dataInStreamPointMaybe: Option[String] = None
  ): BeamFederate = {
    loadHelicsIfNotAlreadyLoaded
    BeamFederate(
      fedName,
      fedInfo,
      bufferSize,
      simulationStep,
      dataOutStreamPointMaybe,
      dataInStreamPointMaybe
    )
  }

  /**
    * Create a Broker instance and a Federate Instance
    *
    * @param brokerName              BROKER_NAME
    * @param numFederates            number of federates to consider
    * @param fedName                 FEDERATE_NAME
    * @param bufferSize              BUFFER_SIZE
    * @param dataOutStreamPointMaybe PUBLICATION_NAME
    * @param dataInStreamPointMaybe  FEDERATE_NAME/SUBSCRIPTION_NAME
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
    simulationStep: Int,
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
      simulationStep,
      dataOutStreamPointMaybe,
      dataInStreamPointMaybe
    )
  }

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

  def createFedInfo(
    coreType: String,
    coreInitString: String,
    timeDeltaProperty: Double,
    intLogLevel: Int
  ): SWIGTYPE_p_void = {
    loadHelicsIfNotAlreadyLoaded
    val fedInfo: SWIGTYPE_p_void = helics.helicsCreateFederateInfo()
    helics.helicsFederateInfoSetCoreTypeFromString(fedInfo, coreType)
    helics.helicsFederateInfoSetCoreInitString(fedInfo, coreInitString)
    helics.helicsFederateInfoSetTimeProperty(fedInfo, HELICS_PROPERTY_TIME_DELTA_get(), timeDeltaProperty)
    helics.helicsFederateInfoSetIntegerProperty(fedInfo, HELICS_PROPERTY_INT_LOG_LEVEL_get(), intLogLevel)
    fedInfo
  }

  def enterExecutionMode(timeout: Duration, federates: BeamFederate*): Unit = {
    import java.util.concurrent.{SynchronousQueue, ThreadPoolExecutor, TimeUnit}
    FileUtils.using(
      new ThreadPoolExecutor(federates.size, federates.size * 2, 0, TimeUnit.SECONDS, new SynchronousQueue[Runnable])
    )(
      _.shutdown()
    ) { executorService =>
      implicit val ec: ExecutionContextExecutorService = ExecutionContext.fromExecutorService(executorService)
      val futureResults = federates.map { beamFederate =>
        Future {
          blocking(helics.helicsFederateEnterExecutingMode(beamFederate.fedComb))
        }
      }
      Await.result(Future.sequence(futureResults), timeout)
    }
  }

  case class BeamFederateDescriptor(
    tazIds: Set[Id[TAZ]],
    chargingStations: List[ChargingStation],
    federate: BeamFederate
  )

  case class BeamFederate(
    fedName: String,
    fedInfo: SWIGTYPE_p_void,
    bufferSize: Int,
    simulationStep: Int,
    dataOutStreamPointMaybe: Option[String] = None,
    dataInStreamPointMaybe: Option[String] = None
  ) extends StrictLogging {
    private var dataOutStreamHandle: Option[SWIGTYPE_p_void] = None
    private var dataInStreamHandle: Option[SWIGTYPE_p_void] = None
    private var currentBin = -1

    // **************************
    val fedComb: SWIGTYPE_p_void = helics.helicsCreateCombinationFederate(fedName, fedInfo)
    logger.info(s"Create combination federate $fedName")
    // ******
    // register new BEAM publications here
    dataOutStreamPointMaybe.foreach(registerPublication)
    // ******
    // register new BEAM subscriptions here
    dataInStreamPointMaybe.foreach(registerSubscription)
    // **************************

    def cosimulate(tick: Int, msgToPublish: List[Map[String, Any]]): List[Map[String, Any]] = {
      def log(message: String): Unit = {
        logger.debug(s"$fedName $message [${System.currentTimeMillis()}]")
      }
      try {
        var msgReceived: Option[List[Map[String, Any]]] = None
        if (currentBin < tick / simulationStep) {
          currentBin = tick / simulationStep
          log(s"Publishing message to the ${dataOutStreamPointMaybe.getOrElse("NA")} at time $tick.")
          publishJSON(msgToPublish)
          log(s"publishNestedJSON $msgToPublish.")
          while (msgReceived.isEmpty) {
            // SYNC
            sync(tick)
            log(s"sync $tick.")
            // COLLECT
            msgReceived = collectJSON()
            log(s"collectedNestedJSON $msgReceived.")
            if (msgReceived.isEmpty) {
              // Sleep
              Thread.sleep(1)
            }
          }
          log(s"Message received from ${dataInStreamPointMaybe.getOrElse("NA")}: $msgReceived.")
        }
        msgReceived.getOrElse(List.empty)
      } catch {
        case ex: Exception =>
          logger.error(s"Exception while cosimulating: $ex")
          throw ex
      }
    }

    /**
      * Convert a list of Key Value Map into an array of JSON documents, then stringifies it before publishing via HELICS
      *
      * @param labeledData List of Key -> Value
      */
    def publishJSON(labeledData: List[Map[String, Any]]): Unit = {
      dataOutStreamHandle.foreach(
        helics.helicsPublicationPublishString(_, labeledData.toJson(ListMapAnyJsonFormat).compactPrint.stripMargin)
      )
    }

    /**
      * Convert a list of values into a string of element separated with a comma, then publishes it via HELICS
      *
      * @param unlabeledData list of values or strings in form of "key:value"
      */
    def publish(unlabeledData: List[Any]): Unit = {
      dataOutStreamHandle.foreach(helics.helicsPublicationPublishString(_, unlabeledData.mkString(",")))
    }

    /**
      * Requests a co-simulation time and wait until it is awarded. The HELICS broker doesn't aware the requested time
      * until all the federates in co-simulation are synchronized
      *
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
      *
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
      *
      * @return Message in List of Maps format
      */
    def collectJSON(): Option[List[Map[String, Any]]] = {
      val message = collectRaw()
      if (message.nonEmpty) {
        try {
          val messageList = message.replace("\u0000", "").parseJson.convertTo[List[Map[String, Any]]]
          Some(messageList)
        } catch {
          case _: Throwable => None
        }
      } else None
    }

    /**
      * Collect list of String messages that has been published by other federates
      * The message is expected to be list of string, if not this method will fail
      *
      * @return message in list of Strings format
      */
    def collectAny(): List[Any] = {
      val message = collectRaw()
      if (message.nonEmpty) {
        message.split(",").toList
      } else List.empty[Any]
    }

    /**
      * Register a publication channel
      *
      * @param pubName the publication id which **is not expected** to be prefixed by a federate name, such as
      *                "PUBLICATION_NAME"
      */
    private def registerPublication(pubName: String): Unit = {
      dataOutStreamHandle = Some(
        helics.helicsFederateRegisterPublication(fedComb, pubName, HelicsDataTypes.HELICS_DATA_TYPE_STRING, "")
      )
      logger.info(s"registering publication $pubName")
    }

    /**
      * Register a subscription channel
      *
      * @param subName the subscription id which **is expected** to be prefixed by a federate name, such as
      *                "FEDERATE_NAME/SUBSCRIPTION_NAME"
      */
    private def registerSubscription(subName: String): Unit = {
      dataInStreamHandle = Some(helics.helicsFederateRegisterSubscription(fedComb, subName, ""))
      logger.info(s"registering subscription $subName")
    }

    /**
      * close HELICS library
      */
    def close(): Unit = {
      logger.info(s"Closing BeamFederate $fedName ...")
      if (helics.helicsFederateIsValid(fedComb) == 1) {
        helics.helicsFederateFinalize(fedComb)
        helics.helicsFederateFree(fedComb)
        logger.info(s"BeamFederate $fedName closed.")
      } else {
        logger.info(s"BeamFederate $fedName is invalid.")
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
    simulationStep: Int,
    dataOutStreamPointMaybe: Option[String] = None,
    dataInStreamPointMaybe: Option[String] = None
  ) extends StrictLogging {
    private val broker = helics.helicsCreateBroker(coreType, "", s"-f $numFederates --name=$brokerName")
    lazy val isConnected: Boolean = helics.helicsBrokerIsConnected(broker) > 0

    private val federate: Option[BeamFederate] = if (isConnected) {
      val fedInfo = createFedInfo(coreType, coreInitString, timeDeltaProperty, intLogLevel)
      Some(
        getFederate(
          fedName,
          fedInfo,
          bufferSize,
          simulationStep,
          dataOutStreamPointMaybe,
          dataInStreamPointMaybe
        )
      )
    } else {
      None
    }

    federate.foreach(enterExecutionMode(10.seconds, _))

    def getBrokersFederate: Option[BeamFederate] = federate
  }
}
