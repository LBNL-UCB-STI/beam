package beam.api

import beam.agentsim.agents.ridehail.{DefaultRideHailDepotParkingManager, RideHailDepotParkingManager}
import beam.agentsim.events.eventbuilder.ComplexEventBuilder
import beam.api.agentsim.agents.ridehail.allocation.{DefaultRepositionManagerFactory, RepositionManagerFactory}
import beam.api.agentsim.agents.ridehail.charging.{
  ChargingManagerFactory,
  DefaultChargingManagerFactory,
  DefaultStallAssignmentStrategyFactory,
  StallAssignmentStrategyFactory
}
import beam.api.agentsim.agents.ridehail.{DefaultRidehailManagerCustomization, RidehailManagerCustomizationAPI}
import beam.api.agentsim.agents.vehicles.BeamVehicleAfterUseFuelHook
import beam.api.sim.termination.{DefaultTerminationCriterionFactory, TerminationCriterionFactory}
import beam.sim.BeamServices
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager

import scala.util.Random

/**
  * Beam customization API and its default implementation.
  */
trait BeamCustomizationAPI {

  /**
    * Allows to provide a custom [[RepositionManagerFactory]].
    * @return
    */
  def getRepositionManagerFactory: RepositionManagerFactory

  /**
    * Allows to provide a custom [[ChargingManagerFactory]].
    * @return
    */
  def getChargingManagerFactory: ChargingManagerFactory

  /**
    * Allows to provide a custom implementation of [[BeamVehicleAfterUseFuelHook]]
    * @return
    */
  def beamVehicleAfterUseFuelHook: Option[BeamVehicleAfterUseFuelHook]

  /**
    * Allows to provide a custom implementation of [[RidehailManagerCustomizationAPI]]
    * @return
    */
  def getRidehailManagerCustomizationAPI: RidehailManagerCustomizationAPI

  /**
    * Allows to register new/custom events with [[BeamEventsLogger]].
    * @param className
    * @return
    */
  def customEventsLogging(className: String): Option[Class[Event]]

  /**
    * Allows to provide a custom [[StallAssignmentStrategyFactory]].
    * @return
    */
  def getStallAssignmentStrategyFactory: StallAssignmentStrategyFactory

  /**
    * A list of custom [[ComplexEventBuilder]]s can be provided.
    * @param eventsManager
    * @return
    */
  def getEventBuilders(eventsManager: EventsManager): List[ComplexEventBuilder]

  /**
    * Allows to specify a custom implementation of [[RideHailDepotParkingManager]].
    * @param beamServices
    * @param boundingBox
    * @return
    */
  def getRideHailDepotParkingManager(beamServices: BeamServices, boundingBox: Envelope): RideHailDepotParkingManager[_]

  /**
    * Allows to provide a custom [[TerminationCriterionFactory]].
    * @return
    */
  def getTerminationCriterionFactory: TerminationCriterionFactory
}

/**
  * Default implementation of [[BeamCustomizationAPI]].
  */
class DefaultAPIImplementation extends BeamCustomizationAPI {
  override def getRepositionManagerFactory: RepositionManagerFactory = new DefaultRepositionManagerFactory()

  override def getChargingManagerFactory: ChargingManagerFactory = new DefaultChargingManagerFactory()

  override def beamVehicleAfterUseFuelHook: Option[BeamVehicleAfterUseFuelHook] = None

  override def getRidehailManagerCustomizationAPI: RidehailManagerCustomizationAPI =
    new DefaultRidehailManagerCustomization()

  override def customEventsLogging(className: String): Option[Class[Event]] = None

  override def getStallAssignmentStrategyFactory: StallAssignmentStrategyFactory =
    new DefaultStallAssignmentStrategyFactory()

  override def getEventBuilders(eventsManager: EventsManager): List[ComplexEventBuilder] = List.empty

  override def getRideHailDepotParkingManager(
    beamServices: BeamServices,
    boundingBox: Envelope
  ): RideHailDepotParkingManager[_] = {
    val parkingFilePath: String = beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.parking.filePath
    val rand: Random = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)

    beamServices.beamConfig.beam.agentsim.taz.parkingManager.level.toLowerCase match {
      case "taz" =>
        DefaultRideHailDepotParkingManager(
          parkingFilePath = parkingFilePath,
          valueOfTime = beamServices.beamConfig.beam.agentsim.agents.rideHail.cav.valueOfTime,
          tazTreeMap = beamServices.beamScenario.tazTreeMap,
          random = rand,
          boundingBox = boundingBox,
          distFunction = beamServices.geo.distUTMInMeters,
          parkingStallCountScalingFactor = beamServices.beamConfig.beam.agentsim.taz.parkingStallCountScalingFactor,
          beamServices = beamServices,
          skims = beamServices.skims,
          outputDirectory = beamServices.matsimServices.getControlerIO
        )
      case "link" =>
        DefaultRideHailDepotParkingManager(
          parkingFilePath = parkingFilePath,
          valueOfTime = beamServices.beamConfig.beam.agentsim.agents.rideHail.cav.valueOfTime,
          linkQuadTree = beamServices.beamScenario.linkQuadTree,
          linkIdMapping = beamServices.beamScenario.linkIdMapping,
          linkToTAZMapping = beamServices.beamScenario.linkToTAZMapping,
          random = rand,
          boundingBox = boundingBox,
          distFunction = beamServices.geo.distUTMInMeters,
          parkingStallCountScalingFactor = beamServices.beamConfig.beam.agentsim.taz.parkingStallCountScalingFactor,
          beamServices = beamServices,
          skims = beamServices.skims,
          outputDirectory = beamServices.matsimServices.getControlerIO
        )
      case wrong @ _ =>
        throw new IllegalArgumentException(s"Unsupported parking level type $wrong, only TAZ | Link are supported")
    }
  }

  override def getTerminationCriterionFactory: TerminationCriterionFactory = new DefaultTerminationCriterionFactory()
}
