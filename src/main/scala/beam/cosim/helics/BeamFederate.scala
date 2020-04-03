package helics

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
  case class BeamFederateTrigger(tick: Int) extends Trigger
  var beamFed = Option.empty[BeamFederate]

  def getInstance(beamServices: BeamServices): BeamFederate = {
    if (beamFed.isEmpty) {
      HelicsLoader.load()
      beamFed = Some(BeamFederate(beamServices))
    }
    beamFed.get
  }
}

case class BeamFederate(beamServices: BeamServices) extends StrictLogging {
  import BeamFederate._
  private val beamConfig = beamServices.beamScenario.beamConfig
  private val tazTreeMap = beamServices.beamScenario.tazTreeMap
  private val registeredEvents = mutable.HashMap.empty[String, SWIGTYPE_p_void]
  private var currentTime: Double = 0.0
  private val fedTimeStep = beamConfig.beam.cosim.helics.timeStep
  private val fedName = beamConfig.beam.cosim.helics.federateName
  private val fedInfo = helics.helicsCreateFederateInfo()
  helics.helicsFederateInfoSetCoreName(fedInfo, fedName)
  helics.helicsFederateInfoSetCoreTypeFromString(fedInfo, "zmq")
  helics.helicsFederateInfoSetCoreInitString(fedInfo, "--federates=1")
  helics.helicsFederateInfoSetTimeProperty(fedInfo, helics_property_time_delta_get(), 1.0)
  helics.helicsFederateInfoSetIntegerProperty(fedInfo, helics_property_int_log_level_get(), 1)
  private val fedComb = helics.helicsCreateCombinationFederate(fedName, fedInfo)

  // ******
  // register new BEAM events here
  registerEvent(ChargingPlugInEvent.EVENT_TYPE, "chargingPlugIn")
  registerEvent(ChargingPlugOutEvent.EVENT_TYPE, "chargingPlugOut")
  // ******

  helics.helicsFederateEnterInitializingMode(fedComb)
  helics.helicsFederateEnterExecutingMode(fedComb)

  // publish
  def publish(event: Event) = {
    if (registeredEvents.contains(event.getEventType)) {
      event match {
        case e: ChargingPlugInEvent =>
          publishChargingEvent(e.getEventType, e.vehId.toString, e.primaryFuelLevel, e.stall.locationUTM)
        case e: ChargingPlugOutEvent =>
          publishChargingEvent(e.getEventType, e.vehId.toString, e.primaryFuelLevel, e.stall.locationUTM)
        case _: RefuelSessionEvent =>
        case _                     =>
      }
    } else {
      logger.error(s"the event '${event.getEventType}' is not registered in BeamFederate")
    }
  }

  def syncAndMoveToNextTimeStep(time: Int): Int = {
    while (currentTime < time) currentTime = helics.helicsFederateRequestTime(fedComb, time)
    fedTimeStep * (1 + (currentTime / fedTimeStep).toInt)
  }

  def close(): Unit = {
    helics.helicsFederateFinalize(fedComb)
    helics.helicsFederateFree(fedComb)
    helics.helicsCloseLibrary()
  }

  private def publishChargingEvent(eventType: String, vehId: String, socInJoules: Double, location: Coord): Unit = {
    val taz = tazTreeMap.getTAZ(location.getX, location.getY)
    val pubVar = s"$vehId,$socInJoules,${taz.coord.getY},${taz.coord.getX}" // VEHICLE,SOC,LAT,LONG
    helics.helicsPublicationPublishString(registeredEvents(eventType), pubVar)
    logger.info(s"publishing at $currentTime the value $pubVar")
  }

  private def registerEvent(eventType: String, pubName: String): Unit = {
    registeredEvents.put(
      eventType,
      helics.helicsFederateRegisterPublication(fedComb, pubName, helics_data_type.helics_data_type_string, "")
    )
  }
}
