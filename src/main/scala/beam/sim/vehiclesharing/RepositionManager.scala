package beam.sim.vehiclesharing
import akka.actor.{Actor, ActorLogging, ActorRef}
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamSkimmer
import beam.sim.BeamServices
import com.vividsolutions.jts.index.quadtree.Quadtree
import org.matsim.api.core.v01.{Coord, Id}

case class REPVehicleRepositionTrigger(tick: Int) extends Trigger
case class REPDataCollectionTrigger(tick: Int) extends Trigger
case class REPVehicleTeleportTrigger(tick: Int, whereWhen: SpaceTime, vehicle: BeamVehicle, idTAZ: Id[TAZ])
    extends Trigger

trait VehicleManager

trait RepositionAlgorithm {
  def getVehiclesForReposition(time: Int): List[(BeamVehicle, SpaceTime, Id[TAZ], SpaceTime, Id[TAZ])]
  def collectData(time: Int, coord: Coord, label: String)
  def collectData(time: Int)
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
  def getREPAlgorithm: RepositionAlgorithm
  def getREPTimeStep: Int
  def getDataCollectTimeStep: Int = 5 * 60

  var currentTick = 0

  getScheduler ! ScheduleTrigger(REPVehicleRepositionTrigger(getREPTimeStep), getActorRef)
  getScheduler ! ScheduleTrigger(REPDataCollectionTrigger(getDataCollectTimeStep), getActorRef)

  override def receive: Receive = {

    case TriggerWithId(REPDataCollectionTrigger(tick), triggerId) =>
      currentTick = tick
      val nextTick = tick + getDataCollectTimeStep
      if(nextTick < 108000) {
        getREPAlgorithm.collectData(tick)
        getScheduler.tell(
          CompletionNotice(
            triggerId,
            Vector(ScheduleTrigger(REPDataCollectionTrigger(nextTick), getActorRef))
          ),
          getActorRef
        )
      }

    case TriggerWithId(REPVehicleRepositionTrigger(tick), triggerId) =>
      val nextTick = tick + getREPTimeStep
      if (getBeamServices.iterationNumber > 0 || getBeamServices.beamConfig.beam.warmStart.enabled) {
        val vehForReposition = getREPAlgorithm.getVehiclesForReposition(tick)
        vehForReposition.filter(rep => makeUnavailable(rep._1.id, rep._1.toStreetVehicle).isDefined).foreach {
          case (vehicle, _, _, dstWhereWhen, dstTAZ) =>
            getScheduler.tell(
              CompletionNotice(
                triggerId,
                Vector(
                  ScheduleTrigger(
                    REPVehicleTeleportTrigger(dstWhereWhen.time, dstWhereWhen, vehicle, dstTAZ),
                    getActorRef
                  )
                )
              ),
              getActorRef
            )
            getREPAlgorithm.collectData(vehicle.spaceTime.time, vehicle.spaceTime.loc, RepositionManager.pickup)
        }
      }
      if (nextTick < 108000) {
        // reschedule
        getScheduler.tell(
          CompletionNotice(
            triggerId,
            Vector(ScheduleTrigger(REPVehicleRepositionTrigger(nextTick), getActorRef))
          ),
          getActorRef
        )
      }

    case TriggerWithId(REPVehicleTeleportTrigger(_, whereWhen, vehicle, _), _) =>
      makeTeleport(vehicle.id, whereWhen)
      makeAvailable(vehicle.id)
      getREPAlgorithm.collectData(vehicle.spaceTime.time, vehicle.spaceTime.loc, RepositionManager.dropoff)
  }

}

object RepositionManager {
  val pickup = "REPPickup"
  val dropoff = "REPDropoff"
  val inquiry = "VEHInquiry"
  val boarded = "VEHBoarded"
  val release = "VEHRelease"
}
