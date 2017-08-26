package beam.agentsim.agents

import akka.actor.Props
import akka.pattern.{ask, pipe}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.RideHailingManager.{RegisterRideAvailable, ReserveRideResponse, RideAvailableAck}
import beam.agentsim.agents.RideHailingAgent._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle
import beam.agentsim.agents.vehicles.BeamVehicle.BeamVehicleIdAndRef
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler._
import beam.agentsim.scheduler.TriggerWithId
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.BeamTrip
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.{Coord, Id}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global

/**
  */
object RideHailingAgent {


  private val logger = LoggerFactory.getLogger(classOf[PersonAgent])

  // syntactic sugar for props creation
  def props(services: BeamServices, taxiId: Id[RideHailingAgent], vehicleIdAndRef: BeamVehicleIdAndRef, location: Coord) =
    Props(new RideHailingAgent(taxiId, RideHailingAgentData(vehicleIdAndRef, location), services))

  //////////////////////////////
  // RideHailingAgentData Begin... //
  /////////////////////////////
  object RideHailingAgentData {
    //    def apply(): TaxiData = TaxiData()
  }

  case class RideHailingAgentData(vehicleIdAndRef: BeamVehicleIdAndRef, location: Coord) extends BeamAgentData

  case object Idle extends BeamAgentState {
    override def identifier = "Idle"
  }

  case object Traveling extends BeamAgentState {
    override def identifier = "Traveling"
  }

  case class PickupCustomer(confirmation: ReserveRideResponse, pickUpLocation: Location, destination: Location, tripPlan: Option[BeamTrip])

  case class DropOffCustomer(newLocation: SpaceTime)

  case class RegisterRideAvailableWrapper(triggerId: Long)

}

class RideHailingAgent(override val id: Id[RideHailingAgent], override val data: RideHailingAgentData, val beamServices: BeamServices) extends BeamAgent[RideHailingAgentData] with HasServices with DrivesVehicle[RideHailingAgentData] {
  override def logPrefix(): String = s"RideHailingAgent $id: "

  chainedWhen(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), info: BeamAgentInfo[RideHailingAgentData]) =>
      val rideAvailable = RegisterRideAvailable(self, info.data.vehicleIdAndRef.id, availableIn = SpaceTime(info.data.location, tick.toLong))
      val managerFuture = (beamServices.rideHailingManager ? rideAvailable).mapTo[RideAvailableAck.type].map(result =>
        RegisterRideAvailableWrapper(triggerId)
      )
      managerFuture pipeTo self
      stay()
    case Event(RegisterRideAvailableWrapper(triggerId), _) =>
      beamServices.schedulerRef ! CompletionNotice(triggerId)
      goto(Idle)
  }

  chainedWhen(Idle) {
    case Event(PickupCustomer, info: BeamAgentInfo[RideHailingAgentData]) =>
      goto(Traveling)
  }

  chainedWhen(Traveling) {
    case Event(DropOffCustomer(newLocation), info: BeamAgentInfo[RideHailingAgentData]) =>
      beamServices.rideHailingManager ? RegisterRideAvailable(self, info.data.vehicleIdAndRef.id, availableIn = newLocation)
      goto(Idle) using BeamAgentInfo(id, info.data.copy(location = newLocation.loc))
  }


  //// BOILERPLATE /////
  when(Idle) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
  }

  when(Traveling) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
  }

  //// END BOILERPLATE ////
  /*
   * Helper methods
  def logInfo(msg: String): Unit = {
    //    log.info(s"PersonAgent $id: $msg")
  }

  def logWarn(msg: String): Unit = {
    log.warning(s"PersonAgent $id: $msg")
  }

  def logError(msg: String): Unit = {
    log.error(s"PersonAgent $id: $msg")
  }

  private def publishPathTraversal(event: PathTraversalEvent): Unit = {
    if(beamConfig.beam.events.pathTraversalEvents contains event.mode){
      agentSimEventsBus.publish(MatsimEvent(event))

    }
  }
   */

}


