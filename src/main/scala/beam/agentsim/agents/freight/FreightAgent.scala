package beam.agentsim.agents.freight

import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.PersonAgent.DrivingData
import beam.agentsim.agents.TransitDriverAgent
import beam.agentsim.agents.modalbehaviors.DrivesVehicle
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.router.model.BeamLeg
import beam.router.osm.TollCalculator
import beam.sim.common.GeoUtils
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.NetworkHelper
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Id
import org.matsim.core.api.experimental.events.EventsManager

/**
  * @author Dmitry Openkov
  */
class FreightAgent(
  val scheduler: ActorRef,
  val beamServices: BeamServices,
  val beamScenario: BeamScenario,
  val transportNetwork: TransportNetwork,
  val tollCalculator: TollCalculator,
  val eventsManager: EventsManager,
  val parkingManager: ActorRef,
  val chargingNetworkManager: ActorRef,
  val transitDriverId: Id[TransitDriverAgent],
  val vehicle: BeamVehicle,
  val legs: Seq[BeamLeg],
  val geo: GeoUtils,
  val networkHelper: NetworkHelper
) extends DrivesVehicle[DrivingData] {
  override val eventBuilderActor: ActorRef = beamServices.eventBuilderActor
  override val id: Id[TransitDriverAgent] = transitDriverId
  override def logPrefix(): String = s"TransitDriverAgent:$id "

}

object FreightAgent {

  def props(
    scheduler: ActorRef,
    beamServices: BeamServices,
    beamScenario: BeamScenario,
    transportNetwork: TransportNetwork,
    tollCalculator: TollCalculator,
    eventsManager: EventsManager,
    parkingManager: ActorRef,
    chargingNetworkManager: ActorRef,
    transitDriverId: Id[TransitDriverAgent],
    vehicle: BeamVehicle,
    legs: Seq[BeamLeg],
    geo: GeoUtils,
    networkHelper: NetworkHelper
  ): Props = {
    Props(
      new FreightAgent(
        scheduler,
        beamServices: BeamServices,
        beamScenario,
        transportNetwork,
        tollCalculator,
        eventsManager,
        parkingManager,
        chargingNetworkManager,
        transitDriverId,
        vehicle,
        legs,
        geo,
        networkHelper
      )
    )
  }
}
