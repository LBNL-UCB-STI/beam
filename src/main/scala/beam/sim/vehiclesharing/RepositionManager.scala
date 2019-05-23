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
  def getScheduler: ActorRef
  def getBeamServices: BeamServices
  def getBeamSkimmer: BeamSkimmer
  def getREPAlgorithm: RepositionAlgorithm
  def getREPTimeStep: Int
  def getDataCollectTimeStep: Int = 5 * 60

  var currentTick = 0
  val eos = 108000

  if (getBeamServices.iterationNumber > 0 || getBeamServices.beamConfig.beam.warmStart.enabled)
    getScheduler ! ScheduleTrigger(REPVehicleRepositionTrigger(getREPTimeStep), self)

  getScheduler ! ScheduleTrigger(REPDataCollectionTrigger(getDataCollectTimeStep), self)

  override def receive: Receive = {

    case TriggerWithId(REPDataCollectionTrigger(tick), triggerId) =>
      currentTick = tick
      val nextTick = tick + getDataCollectTimeStep
      if (nextTick < eos) {
        getREPAlgorithm.collectData(tick)
        sender ! CompletionNotice(triggerId, Vector(ScheduleTrigger(REPDataCollectionTrigger(nextTick), self)))
      } else {
        sender ! CompletionNotice(triggerId)
      }

    case TriggerWithId(REPVehicleRepositionTrigger(tick), triggerId) =>
      val nextTick = tick + getREPTimeStep
      if (nextTick < eos) {
        val vehForReposition = getREPAlgorithm.getVehiclesForReposition(tick)
        val triggers = vehForReposition.filter(rep => makeUnavailable(rep._1.id, rep._1.toStreetVehicle).isDefined).map {
          case (vehicle, _, _, dstWhereWhen, dstTAZ) =>
            getREPAlgorithm.collectData(vehicle.spaceTime.time, vehicle.spaceTime.loc, RepositionManager.pickup)
            ScheduleTrigger(REPVehicleTeleportTrigger(dstWhereWhen.time, dstWhereWhen, vehicle, dstTAZ), self)
        }.toVector
        sender ! CompletionNotice(triggerId, triggers :+ ScheduleTrigger(REPVehicleRepositionTrigger(nextTick), self))
      } else {
        sender ! CompletionNotice(triggerId)
      }

    case TriggerWithId(REPVehicleTeleportTrigger(_, whereWhen, vehicle, _), triggerId) =>
      makeTeleport(vehicle.id, whereWhen)
      makeAvailable(vehicle.id)
      getREPAlgorithm.collectData(vehicle.spaceTime.time, vehicle.spaceTime.loc, RepositionManager.dropoff)
      sender ! CompletionNotice(triggerId)
  }

}

object RepositionManager {
  val pickup = "REPPickup"
  val dropoff = "REPDropoff"
  val inquiry = "VEHInquiry"
  val boarded = "VEHBoarded"
  val release = "VEHRelease"
}
