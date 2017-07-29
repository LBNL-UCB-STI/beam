package beam.agentsim.agents

import akka.actor.Props
import akka.pattern.{ask, pipe}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.scheduler.BeamAgentScheduler._
import beam.agentsim.agents.TaxiAgent._
import beam.agentsim.agents.RideHailingManager.{RegisterTaxiAvailable, ReserveTaxiResponse, TaxiAvailableAck}
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.TriggerWithId
import beam.router.BeamRouter.RouteLocation
import beam.router.RoutingModel.BeamTrip
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global

/**
  */
object TaxiAgent {

  private val logger = LoggerFactory.getLogger(classOf[PersonAgent])

  // syntactic sugar for props creation
  def props(taxiId: Id[TaxiAgent], taxiData: TaxiData, services: BeamServices) = Props(classOf[TaxiAgent], taxiId, taxiData, services)

  //////////////////////////////
  // TNCData Begin... //
  /////////////////////////////
  object TaxiData {
//    def apply(): TaxiData = TaxiData()
  }
  case class TaxiData(vehicleId: Id[Vehicle], location: Coord) extends BeamAgentData

  case object Idle extends BeamAgentState {
    override def identifier = "Idle"
  }
  case object Traveling extends BeamAgentState {
    override def identifier = "Traveling"
  }
  case class PickupCustomer(confirmation: ReserveTaxiResponse, pickUpLocation: RouteLocation, destination: RouteLocation, tripPlan: Option[BeamTrip])
  case class DropOffCustomer(newLocation: SpaceTime)

  case class RegisterTaxiAvailableWrapper(triggerId: Long)
}

class TaxiAgent(override val id: Id[TaxiAgent], override val data: TaxiData, val beamServices: BeamServices) extends BeamAgent[TaxiData] with HasServices {

  when(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), info: BeamAgentInfo[TaxiData]) =>
      val taxiAvailable = RegisterTaxiAvailable(self, info.data.vehicleId, availableIn = SpaceTime(info.data.location, tick.toLong))
      val managerFuture = (beamServices.taxiManager ? taxiAvailable).mapTo[TaxiAvailableAck.type].map(result =>
        RegisterTaxiAvailableWrapper(triggerId)
      )
      managerFuture pipeTo self
      stay()
    case Event(RegisterTaxiAvailableWrapper(triggerId), _) =>
      beamServices.schedulerRef ! CompletionNotice(triggerId)
      goto(Idle)
  }

  when(Idle) {
    case Event(PickupCustomer, info: BeamAgentInfo[TaxiData]) =>
      goto(Traveling)
  }

  when(Traveling) {
    case Event(DropOffCustomer(newLocation), info: BeamAgentInfo[TaxiData]) =>
      beamServices.taxiManager ? RegisterTaxiAvailable(self, info.data.vehicleId, availableIn =  newLocation)
      goto(Idle) using BeamAgentInfo(id,info.data.copy(location = newLocation.loc))
  }

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


