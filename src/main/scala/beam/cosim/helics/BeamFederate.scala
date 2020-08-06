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

object BeamFederate {
  // Lazy makes sure that it is initialized only once
  lazy val loadHelics: Unit = {
    HelicsLoader.load()
  }

  case class BeamFederateTrigger(tick: Int) extends Trigger

  var beamFed = Option.empty[BeamFederate]

  def getInstance(beamServices: BeamServices): BeamFederate = this.synchronized {
    if (beamFed.isEmpty) {
      loadHelics
      beamFed = Some(BeamFederate(beamServices))
    }
    beamFed.get
  }
}

case class BeamFederate(beamServices: BeamServices) extends StrictLogging {
  private val beamConfig = beamServices.beamScenario.beamConfig
  private val tazTreeMap = beamServices.beamScenario.tazTreeMap
  private val registeredEvents = mutable.HashMap.empty[String, SWIGTYPE_p_void]
  private val fedTimeStep = beamConfig.beam.cosim.helics.timeStep
  private val fedName = beamConfig.beam.cosim.helics.federateName
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
  // register new BEAM events here
  registerEvent(ChargingPlugInEvent.EVENT_TYPE, "chargingPlugIn")
  registerEvent(ChargingPlugOutEvent.EVENT_TYPE, "chargingPlugOut")
  registerEvent(RefuelSessionEvent.EVENT_TYPE, "refuelSession")
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

  private def registerEvent(eventType: String, pubName: String): Unit = {
    registeredEvents.put(
      eventType,
      helics.helicsFederateRegisterPublication(fedComb, pubName, helics_data_type.helics_data_type_string, "")
    )
    logger.debug(s"registering $pubName to CombinationFederate")
  }
}
