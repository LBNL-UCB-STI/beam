package beam.cosim.helics

import beam.agentsim.events.{ChargingPlugInEvent, ChargingPlugOutEvent, RefuelSessionEvent}
import beam.agentsim.scheduler.Trigger
import beam.sim.BeamServices
import com.github.beam.HelicsLoader
import com.java.helics._
import com.java.helics.helicsJNI._
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.events.Event

import scala.collection.mutable
import scala.util.parsing.json._
import spray.json._
import DefaultJsonProtocol._

import scala.reflect.ClassTag

object BeamFederate {
  // Lazy makes sure that it is initialized only once
  lazy val loadHelics: Unit = {
    HelicsLoader.load()
  }

  case class BeamFederateTrigger(tick: Int) extends Trigger

  var beamFed = Option.empty[BeamFederate]

  def getInstance(beamServices: BeamServices, fedName: String, fedTimeStep: Int): BeamFederate = this.synchronized {
    if (beamFed.isEmpty) {
      loadHelics
      beamFed = Some(BeamFederate(beamServices, fedName, fedTimeStep))
    }
    beamFed.get
  }

  def destroyInstance(): Unit = this.synchronized {
    helics.helicsCleanupLibrary()
    helics.helicsCloseLibrary()
    beamFed = None
  }

//  import scala.reflect.ClassTag
//  import scala.reflect.runtime.universe._
//  abstract class BeamFederateMessage() {
//    def classAccessors[T: TypeTag]: String = typeOf[T].members.collect {
//      case m: MethodSymbol if m.isCaseAccessor => m.name
//    }.mkString("\t")
//  }
}

case class BeamFederate(beamServices: BeamServices, fedName: String, fedTimeStep: Int) extends StrictLogging {
  private val beamConfig = beamServices.beamScenario.beamConfig
  private val tazTreeMap = beamServices.beamScenario.tazTreeMap
  private val registeredEvents = mutable.HashMap.empty[String, SWIGTYPE_p_void]
  private val registeredSubscriptions = mutable.HashMap.empty[String, SWIGTYPE_p_void]
  private val fedInfo = helics.helicsCreateFederateInfo()
  helics.helicsFederateInfoSetCoreName(fedInfo, fedName)
  helics.helicsFederateInfoSetCoreTypeFromString(fedInfo, "zmq")
  helics.helicsFederateInfoSetCoreInitString(fedInfo, "--federates=1")
  helics.helicsFederateInfoSetTimeProperty(fedInfo, helics_property_time_delta_get(), 1.0)
  helics.helicsFederateInfoSetIntegerProperty(fedInfo, helics_property_int_log_level_get(), 1)
  logger.debug(s"FederateInfo created")
  private val fedComb = helics.helicsCreateCombinationFederate(fedName, fedInfo)
  logger.debug(s"CombinationFederate created")
  // Constants
  private val PowerOverNextInterval = "PowerOverNextInterval"
  private val PowerFlow = "PowerFlow"
  // ******
  // register new BEAM events here
  registerEvent[String](ChargingPlugInEvent.EVENT_TYPE, "chargingPlugIn")
  registerEvent[String](ChargingPlugOutEvent.EVENT_TYPE, "chargingPlugOut")
  registerEvent[String](RefuelSessionEvent.EVENT_TYPE, "refuelSession")
  registerEvent[Double](PowerOverNextInterval, "powerOverNextInterval")
  // ******
  // register new BEAM subscriptions here
  registerSubscription(PowerFlow, "GridFederate/powerFlow")
  // ******

  helics.helicsFederateEnterInitializingMode(fedComb)
  logger.debug(s"Federate initialized and wait for Executing Mode to be granted")
  helics.helicsFederateEnterExecutingMode(fedComb)
  logger.debug(s"Federate successfully entered the Executing Mode")

  // publish
  def publish(event: Event, currentTime: Double): Unit = {
    if (registeredEvents.contains(event.getEventType)) {
      event match {
        case e: ChargingPlugInEvent =>
          publishChargingEvent(currentTime, e.getEventType, e.vehId.toString, e.primaryFuelLevel, e.stall.locationUTM)
        case e: ChargingPlugOutEvent =>
          publishChargingEvent(currentTime, e.getEventType, e.vehId.toString, e.primaryFuelLevel, e.stall.locationUTM)
        case _: RefuelSessionEvent =>
        case _                     =>
      }
    } else {
      logger.error(s"the event '${event.getEventType}' was not registered")
    }
  }

  def publishPowerOverPlanningHorizon(value: JsValue): Unit = {
    helics.helicsFederatePublishJSON(registeredEvents(PowerOverNextInterval), value.compactPrint)
    logger.debug("Sent load over next interval to the grid")
  }

  def obtainPowerFlowValue: JsValue = {
    val buffer = new Array[Byte](1000)
    val bufferInt = new Array[Int](1)
    helics.helicsInputGetString(registeredSubscriptions(PowerFlow), buffer, bufferInt)
    val value = buffer.take(bufferInt(0)).map(_.toChar).mkString.parseJson
    logger.debug("Received physical bounds from the grid")
    value
  }

  def syncAndMoveToNextTimeStep(time: Int): Int = {
    var currentTime = -1.0
    logger.debug(s"requesting the time $time from the broker")
    while (currentTime < time) currentTime = helics.helicsFederateRequestTime(fedComb, time)
    logger.debug(s"the time $time granted was $currentTime")
    fedTimeStep * (1 + (currentTime / fedTimeStep).toInt)
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

  private def publishChargingEvent(
    currentTime: Double,
    eventType: String,
    vehId: String,
    socInJoules: Double,
    location: Coord
  ): Unit = {
    val taz = tazTreeMap.getTAZ(location.getX, location.getY)
    val pubVar = s"$vehId,$socInJoules,${taz.coord.getY},${taz.coord.getX}" // VEHICLE,SOC,LAT,LONG
    helics.helicsPublicationPublishString(registeredEvents(eventType), pubVar)
    logger.debug(s"publishing at $currentTime the value $pubVar")
  }

  private def registerEvent[A](eventType: String, pubName: String)(implicit tag: ClassTag[A]): Unit = {
    registeredEvents.put(
      eventType,
      helics.helicsFederateRegisterPublication(fedComb, pubName, eventClassMapper[A], "")
    )
    logger.debug(s"registering $pubName to CombinationFederate")
  }

  private def registerSubscription(eventType: String, pubName: String): Unit = {
    registeredSubscriptions.put(
      eventType,
      helics.helicsFederateRegisterSubscription(fedComb, pubName, "")
    )
    logger.debug(s"registering $pubName to CombinationFederate")
  }

  private def eventClassMapper[A](implicit tag: ClassTag[A]): helics_data_type = {
    val stringClass = classOf[String]
    tag match {
      case ClassTag(`stringClass`) => helics_data_type.helics_data_type_string
      case ClassTag.Double         => helics_data_type.helics_data_type_double
      case ClassTag.Int            => helics_data_type.helics_data_type_int
      case ClassTag.Boolean        => helics_data_type.helics_data_type_boolean
      case ClassTag.Object         => helics_data_type.helics_data_type_complex
      case ClassTag.Any            => helics_data_type.helics_data_type_any
    }
  }
}
