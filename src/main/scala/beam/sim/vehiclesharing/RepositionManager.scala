package beam.sim.vehiclesharing
import akka.actor.{Actor, ActorLogging, ActorRef}
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.taz.TAZ
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.skim.TAZSkimmerEvent
import beam.router.skim.TAZSkimsCollector.TAZSkimsCollectionTrigger
import beam.sim.BeamServices
import org.matsim.api.core.v01.{Coord, Id}

trait RepositionManager extends Actor with ActorLogging {

  var currentTick = 0
  val eos = 108000

  val (algorithm, repTime, statTime) = getRepositionAlgorithmType match {
    case Some(algorithmType) =>
      var alg: RepositionAlgorithm = null
      if (getServices.matsimServices.getIterationNumber > 0 || getServices.beamConfig.beam.warmStart.enabled) {
        alg = algorithmType.getInstance(getId, getServices)
        getScheduler ! ScheduleTrigger(REPVehicleRepositionTrigger(algorithmType.getRepositionTimeBin), self)
      }
      (alg, algorithmType.getRepositionTimeBin, algorithmType.getStatTimeBin)
    case _ =>
      (null, 0, 1)
  }

  // ****
  def getId: Id[VehicleManager]
  def queryAvailableVehicles: List[BeamVehicle]
  def getAvailableVehicles: Iterable[BeamVehicle]
  def makeUnavailable(vehId: Id[BeamVehicle], streetVehicle: StreetVehicle): Option[BeamVehicle]
  def makeAvailable(vehId: Id[BeamVehicle]): Boolean
  def makeTeleport(vehId: Id[BeamVehicle], whenWhere: SpaceTime): Unit
  def getScheduler: ActorRef
  def getServices: BeamServices
  def getRepositionAlgorithmType: Option[RepositionAlgorithmType]

  // ***
  override def receive: Receive = {
    case TAZSkimsCollectionTrigger(tick) =>
      queryAvailableVehicles.foreach(v => collectData(tick, v.spaceTime.loc, RepositionManager.availability))

    case TriggerWithId(REPVehicleRepositionTrigger(tick), triggerId) =>
      val nextTick = tick + repTime
      if (nextTick < eos) {
        val vehForReposition =
          algorithm.getVehiclesForReposition(tick, repTime, queryAvailableVehicles)
        val triggers = vehForReposition
          .filter(rep => makeUnavailable(rep._1.id, rep._1.toStreetVehicle).isDefined)
          .map {
            case (vehicle, _, _, dstWhereWhen, dstTAZ) =>
              collectData(vehicle.spaceTime.time, vehicle.spaceTime.loc, RepositionManager.pickup)
              ScheduleTrigger(REPVehicleTeleportTrigger(dstWhereWhen.time, dstWhereWhen, vehicle, dstTAZ), self)
          }
          .toVector
        sender ! CompletionNotice(triggerId, triggers :+ ScheduleTrigger(REPVehicleRepositionTrigger(nextTick), self))
      } else {
        sender ! CompletionNotice(triggerId)
      }

    case TriggerWithId(REPVehicleTeleportTrigger(_, whereWhen, vehicle, _), triggerId) =>
      makeTeleport(vehicle.id, whereWhen)
      makeAvailable(vehicle.id)
      collectData(vehicle.spaceTime.time, vehicle.spaceTime.loc, RepositionManager.dropoff)
      sender ! CompletionNotice(triggerId)
  }

  protected def collectData(time: Int, loc: Coord, varLabel: String): Unit = {
    getServices.matsimServices.getEvents.processEvent(
      TAZSkimmerEvent(time, loc, varLabel, 1.0, getServices, "RepositionManager")
    )
  }
}

case class REPVehicleRepositionTrigger(tick: Int) extends Trigger
case class REPVehicleTeleportTrigger(tick: Int, whereWhen: SpaceTime, vehicle: BeamVehicle, idTAZ: Id[TAZ])
    extends Trigger

trait RepositionAlgorithm {

  def getVehiclesForReposition(
    time: Int,
    timeBin: Int,
    availableFleet: List[BeamVehicle]
  ): List[(BeamVehicle, SpaceTime, Id[TAZ], SpaceTime, Id[TAZ])]
}
case class RepositionModule(algorithm: RepositionAlgorithm, timeBin: Int, statTimeBin: Int)

object RepositionManager {
  val pickup = "pickup"
  val dropoff = "dropoff"
  val inquiry = "inquiry"
  val boarded = "boarded"
  val release = "released"
  val availability = "idleVehicles"
}
