package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.Props
import akka.pattern.{ask, pipe}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.scheduler.BeamAgentScheduler._
import beam.agentsim.agents.TaxiAgent._
import beam.agentsim.agents.TaxiManager.{RegisterTaxiAvailable, TaxiAvailableAck}
import beam.sim.BeamServices
import org.matsim.api.core.v01.{Coord, Id}
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
  case class TaxiData(location: Coord) extends BeamAgentData

  case object Idle extends BeamAgentState {
    override def identifier = "Idle"
  }
  case object Traveling extends BeamAgentState {
    override def identifier = "Traveling"
  }
  case object PickupCustomer
  case class DropOffCustomer(newLocation: Coord)

  case class RegisterTaxiAvailableWrapper(triggerId: Long)
}

class TaxiAgent(override val id: Id[TaxiAgent], override val data: TaxiData, val services: BeamServices) extends BeamAgent[TaxiData] {

  import beam.sim.BeamServices._

  private implicit val timeout = akka.util.Timeout(5000, TimeUnit.SECONDS)

  when(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), info: BeamAgentInfo[TaxiData]) =>
      val managerFuture = (services.taxiManager ? RegisterTaxiAvailable(self,info.data.location)).mapTo[TaxiAvailableAck.type].map(result =>
        RegisterTaxiAvailableWrapper(triggerId)
      )
      managerFuture pipeTo self
      stay()
    case Event(RegisterTaxiAvailableWrapper(triggerId), _) =>
      services.schedulerRef ! CompletionNotice(triggerId)
      goto(Idle)
  }

  when(Idle) {
    case Event(PickupCustomer, info: BeamAgentInfo[TaxiData]) =>
      goto(Traveling)
  }

  when(Traveling) {
    case Event(DropOffCustomer(newLocation), info: BeamAgentInfo[TaxiData]) =>
      services.taxiManager ? RegisterTaxiAvailable(self,newLocation)
      goto(Idle) using BeamAgentInfo(id,info.data.copy(location = newLocation))
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


