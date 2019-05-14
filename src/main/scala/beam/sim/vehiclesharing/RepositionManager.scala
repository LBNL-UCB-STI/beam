package beam.sim.vehiclesharing
import akka.actor.{Actor, ActorLogging, ActorRef}
import beam.agentsim.agents.household.HouseholdActor.ReleaseVehicleAndReply
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamSkimmer
import beam.sim.BeamServices
import com.vividsolutions.jts.geom.{Coordinate, Envelope}
import com.vividsolutions.jts.index.quadtree.Quadtree
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

case class REPVehicleInquiry(personId: Id[Person], whereWhen: SpaceTime)
case class REPVehicleRepositionTrigger(tick: Int) extends Trigger
case class REPVehicleTeleportTrigger(tick: Int, whereWhen: SpaceTime, vehicle: BeamVehicle, idTAZ: Id[TAZ])
    extends Trigger

trait VehicleManager

trait RepositionAlgorithm {

  def getVehiclesForReposition(
    startTime: Int,
    endTime: Int,
    repositionManager: RepositionManager
  ): List[(BeamVehicle, SpaceTime, Id[TAZ], SpaceTime, Id[TAZ])]

  def collectData(time: Int, repositionManager: RepositionManager)
}

trait RepositionManager extends Actor with ActorLogging {
  def getId: Id[VehicleManager]
  def getAvailableVehiclesIndex: Quadtree
  def makeUnavailable(vehId: Id[BeamVehicle], streetVehicle: StreetVehicle): Option[BeamVehicle]
  def makeAvailable(vehId: Id[BeamVehicle]): Boolean
  def makeTeleport(vehId: Id[BeamVehicle], whenWhere: SpaceTime): Unit
  def getActorRef: ActorRef
  def getScheduler: ActorRef
  def getBeamServices: BeamServices
  def getBeamSkimmer: BeamSkimmer
  def getAlgorithm: RepositionAlgorithm
  def getTimeStep: Int
  def getDemandLabel: String = "demand"
  def getRepositionManagerListenerInstance: RepositionManagerListener

  getScheduler ! ScheduleTrigger(REPVehicleRepositionTrigger(0), getActorRef)

  override def receive: Receive = {

    case TriggerWithId(REPVehicleRepositionTrigger(tick), triggerId) =>
      val nextTick = tick + getTimeStep
      if(tick > 0) {
        getAlgorithm.collectData(tick, this) // collecting
        println(s"availability ===> ${getAvailableVehiclesIndex.size()}")
        getAlgorithm.getVehiclesForReposition(tick, nextTick, this).foreach {
          case (vehicle, orgWhereWhen, orgTAZ, dstWhereWhen, dstTAZ) =>
            // reposition
            getRepositionManagerListenerInstance.pickupEvent(orgWhereWhen.time, orgTAZ, getId, vehicle.id, "default")
            getScheduler.tell(
              CompletionNotice(
                triggerId,
                Vector(ScheduleTrigger(REPVehicleTeleportTrigger(dstWhereWhen.time, dstWhereWhen, vehicle, dstTAZ), getActorRef))
              ),
              getActorRef
            )
        }
      }
      // reschedule
      getScheduler.tell(
        CompletionNotice(
          triggerId,
          Vector(ScheduleTrigger(REPVehicleRepositionTrigger(nextTick), getActorRef))
        ),
        getActorRef
      )

    case TriggerWithId(REPVehicleTeleportTrigger(tick, whereWhen, vehicle, idTAZ), _) =>
      makeTeleport(vehicle.id, whereWhen)
      vehicle.manager.get ! ReleaseVehicleAndReply(vehicle, Some(tick))
      getRepositionManagerListenerInstance.dropoffEvent(whereWhen.time, idTAZ, getId, vehicle.id, "default")

    case REPVehicleInquiry(personId, whenWhere) =>
      getBeamSkimmer.observeVehicleDemandByTAZ(whenWhere.time, whenWhere.loc, getId, getDemandLabel, Some(personId))

  }

}
