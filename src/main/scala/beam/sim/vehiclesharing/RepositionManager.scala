package beam.sim.vehiclesharing
import akka.actor.{Actor, ActorLogging, ActorRef}
import beam.agentsim.agents.household.HouseholdActor.ReleaseVehicleAndReply
import beam.agentsim.agents.vehicles.BeamVehicle
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

case class REPVehicleReposition(vehicle: BeamVehicle, whereWhen: SpaceTime, idTAZ: Id[TAZ])
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
  ): List[(BeamVehicle, SpaceTime, Id[TAZ])]

  def collectData(time: Int, repositionManager: RepositionManager)
}

trait RepositionManager extends Actor with ActorLogging {
  def getId: Id[VehicleManager]
  def getAvailableVehicles: Quadtree
  def getActorRef: ActorRef
  def getScheduler: ActorRef
  def getBeamServices: BeamServices
  def getBeamSkimmer: BeamSkimmer
  def getRepositionAlgorithm: RepositionAlgorithm
  def getTimeStep: Int
  def getDemandLabel: String = "demand"
  def getRepositionManagerListenerInstance: RepositionManagerListener

  getScheduler ! ScheduleTrigger(REPVehicleRepositionTrigger(0), getActorRef)

  override def receive: Receive = {
    case TriggerWithId(REPVehicleRepositionTrigger(tick), triggerId) =>
      // collecting
      getRepositionAlgorithm.collectData(tick, this)

      // repositioning
      val nextTick = tick + getTimeStep
      getRepositionAlgorithm.getVehiclesForReposition(tick, nextTick, this) foreach {
        case (vehicle, whereWhen, idTAZ) =>
          vehicle.manager.get ! REPVehicleReposition(vehicle, whereWhen, idTAZ)
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
      vehicle.spaceTime = SpaceTime(whereWhen.loc, tick)
      vehicle.manager.get ! ReleaseVehicleAndReply(vehicle, Some(tick))
      getRepositionManagerListenerInstance.dropoffEvent(whereWhen.time, idTAZ, getId, vehicle.id, "default")
    case REPVehicleReposition(vehicle, whereWhen, idTAZ) =>
      val removed = getAvailableVehicles.remove(
        new Envelope(new Coordinate(vehicle.spaceTime.loc.getX, vehicle.spaceTime.loc.getY)),
        vehicle
      )
      if (!removed) {
        log.error("Didn't find a vehicle in my spatial index, at the location I thought it would be.")
      } else {
        getRepositionManagerListenerInstance.pickupEvent(whereWhen.time, idTAZ, getId, vehicle.id, "default")
      }
      getScheduler ! ScheduleTrigger(REPVehicleTeleportTrigger(whereWhen.time, whereWhen, vehicle, idTAZ), getActorRef)
    case REPVehicleInquiry(personId, whenWhere) =>
      getBeamSkimmer.observeVehicleDemandByTAZ(whenWhere.time, whenWhere.loc, getId, getDemandLabel, Some(personId))

  }

}
