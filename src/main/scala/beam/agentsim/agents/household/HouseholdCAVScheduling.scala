package beam.agentsim.agents.household
import java.util.concurrent.atomic.AtomicInteger

import beam.agentsim.agents.planning.BeamPlan
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.RoutingRequest
import beam.router.Modes.BeamMode.CAV
import beam.sim.BeamServices
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.population._
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.vehicles.Vehicle

import scala.collection.immutable.Map
import scala.collection.immutable.List

sealed trait MobilityServiceRequestType
case object Pickup extends MobilityServiceRequestType
case object Dropoff extends MobilityServiceRequestType
case object Relocation extends MobilityServiceRequestType
case object Init extends MobilityServiceRequestType

case class MobilityServiceRequest(
  person: Option[Id[Person]],
  activity: Activity,
  time: Double,
  deltaTime: Double,
  tag: MobilityServiceRequestType,
  nextActivity: Option[Activity] = None,
  routingRequestId: Option[Int] = None
) {
  override def toString =
    s"$tag{ ${person match {
      case Some(x) => x.toString
      case None    => "NA"
    }}|${activity.getType}|${(time / 3600).toInt}:${((time % 3600) / 60).toInt}:${(time % 60).toInt}|ocp:$deltaTime }"
}

class HouseholdPlansToMSR(plans: List[BeamPlan], skim: Map[Coord, Map[Coord, Double]]) {
  var requests = List[MobilityServiceRequest]()
  for (plan <- plans) {
    plan.activities.sliding(2).foreach{ activityTuple =>
      requests = requests :+ new MobilityServiceRequest(
        Some(plan.getPerson.getId),
        activityTuple(1),
        activityTuple(0).getEndTime + skim(activityTuple(0).getCoord)(activityTuple(1).getCoord),
        0.0,
        Dropoff
      )
    }
    plan.activities.sliding(2).foreach{ activityTuple =>
      if(activityTuple.size==2){
        requests = requests :+ new MobilityServiceRequest(
          Some(plan.getPerson.getId),
          activityTuple(0),
          activityTuple(0).getEndTime,
          0.0,
          Pickup,
          nextActivity = Some(activityTuple(1))
        )
      }
    }
  }
  requests = requests.sortWith(_.time < _.time)
  override def toString = s"${requests}"
}

class HouseholdCAVScheduling(
  plans: List[BeamPlan],
  cavVehicles: List[BeamVehicle],
  timeWindow: Double,
  skim: Map[Coord, Map[Coord, Double]]
) {
  private var fleet = cavVehicles
  private var feasibleSchedules = List[HouseholdSchedule]()

  case class HouseholdCAV(id: Id[BeamVehicle], maxOccupancy: Int)

  def apply(): List[HouseholdSchedule] = {

    // extract potential household CAV requests from plans
    val householdRequests = new HouseholdPlansToMSR(plans, skim);

    // deploy the fleet or set up the initial household schedule
    var emptyFleetSchedule = List[CAVSchedule]()
    fleet.foreach(
      veh =>
        emptyFleetSchedule = emptyFleetSchedule :+ new CAVSchedule(
          List[MobilityServiceRequest](
            new MobilityServiceRequest(
              None,
              householdRequests.requests.head.activity,
              householdRequests.requests.head.time - 1,
              1,
              Init
            )
          ),
          veh,
          0,
          0
      ) // initial Cost and Occupancy
    )
    feasibleSchedules = feasibleSchedules :+ new HouseholdSchedule(emptyFleetSchedule)

    // extract all possible schedule combinations
    for (request <- householdRequests.requests) {
      var householdSchedulesToAdd = List[HouseholdSchedule]()
      var householdSchedulesToDelete = List[HouseholdSchedule]()
      for (schedule <- feasibleSchedules) {
        householdSchedulesToAdd ++= schedule.check(request,skim,timeWindow)
        if (!schedule.feasible) {
          householdSchedulesToDelete = householdSchedulesToDelete :+ schedule
        }
      }
      feasibleSchedules = feasibleSchedules.diff(householdSchedulesToDelete) ++ householdSchedulesToAdd
    }
    feasibleSchedules
  }

}

class HouseholdSchedule(val cavFleetSchedule: List[CAVSchedule]) {
  var feasible: Boolean = true
  var cost: Double = 0
  cavFleetSchedule.foreach { x =>
    cost += x.cost
  }

  def check(request: MobilityServiceRequest,skim: Map[Coord,Map[Coord,Double]], timeWindow: Double): List[HouseholdSchedule] = {
    var newHouseholdSchedule = List[HouseholdSchedule]()
    for (cavSchedule <- cavFleetSchedule) {
      cavSchedule.check(request,skim,timeWindow) match {
        case Some(x) =>
          newHouseholdSchedule = newHouseholdSchedule :+ new HouseholdSchedule(
            (cavFleetSchedule.filter(_ != cavSchedule)) :+ x
          )
        case None => //Nothing to do here
      }
      feasible = feasible && cavSchedule.feasible
    }
    newHouseholdSchedule
  }
  override def toString = {
    var output = s"Household Schedule - COST:${cost}.\n"
    cavFleetSchedule.foreach { i =>
      output += s"${i}\n"
    }
    output
  }
}
class CAVSchedule(val schedule: List[MobilityServiceRequest], val cav: BeamVehicle, val cost: Double, val occupancy: Int) {
  var feasible: Boolean = true

  def check(request: MobilityServiceRequest, skim: Map[Coord,Map[Coord,Double]], timeWindow: Double): Option[CAVSchedule] = {
    val travelTime = skim(schedule.last.activity.getCoord)(request.activity.getCoord)
    val arrivalTime = schedule.last.time + schedule.last.deltaTime + travelTime
    val newDeltaTime = arrivalTime - request.time
    val newCost = cost + newDeltaTime
    var newCavSchedule: Option[CAVSchedule] = None
    request.tag match {
      case Pickup =>
        if (occupancy == 0 && newDeltaTime < -1 * timeWindow) {
          val relocationRequest =
            new MobilityServiceRequest(None, request.activity, request.time - 1, newDeltaTime, Relocation)
          val newRequest = new MobilityServiceRequest(request.person, request.activity, request.time, 0, Pickup, request.nextActivity)
          newCavSchedule = Some(
            new CAVSchedule(schedule :+ relocationRequest :+ newRequest, cav, newCost, occupancy + 1)
          )
        } else if (occupancy < cav.beamVehicleType.seatingCapacity && math.abs(newDeltaTime) <= timeWindow) {
          val newRequest =
            new MobilityServiceRequest(request.person, request.activity, request.time, newDeltaTime, Pickup, request.nextActivity)
          newCavSchedule = Some(new CAVSchedule(schedule :+ newRequest, cav, newCost, occupancy + 1))
        } else {
          // Dead End, Not going down this branch
        }
      case Dropoff =>
        val index = schedule.lastIndexWhere(_.person == request.person)
        if (index < 0 || schedule(index).tag != Pickup) {
          // Dead End, Not going down this branch
        } else if (math.abs(newDeltaTime) > timeWindow) {
          // Current Schedule unfeasible, to be marked for removal
          feasible = false
        } else {
          val newRequest =
            new MobilityServiceRequest(request.person, request.activity, request.time, newDeltaTime, Dropoff)
          newCavSchedule = Some(new CAVSchedule(schedule :+ newRequest, cav, newCost, occupancy - 1))
          feasible = false
        }
      case _ => // No Action
    }
    newCavSchedule
  }
  def toRoutingRequests(beamServices: BeamServices): (List[Option[RoutingRequest]], CAVSchedule) = {
    var newMobilityRequests = List[MobilityServiceRequest]()
    var requestList = schedule.sliding(2).filter(_.size>1).map{ wayPoints =>
      val orig = wayPoints(0)
      val dest = wayPoints(1)
      if(beamServices.geo.distUTMInMeters(orig.activity.getCoord,dest.activity.getCoord) < 50){
        // We ignore this in favor of creating a dummy car leg
        None
      }else{
        val origin = SpaceTime(orig.activity.getCoord,math.round(orig.activity.getEndTime).toInt)
        val routingRequest = RoutingRequest(
          orig.activity.getCoord,
          dest.activity.getCoord,
          origin.time,
          IndexedSeq(),
          IndexedSeq(StreetVehicle(Id.create(cav.id.toString,classOf[Vehicle]), cav.beamVehicleType.id,origin,CAV,true))
        )
        newMobilityRequests = newMobilityRequests :+ orig.copy(routingRequestId = Some(routingRequest.requestId))
        Some(routingRequest)
      }
    }.toList
    (requestList, new CAVSchedule(newMobilityRequests, cav, cost, occupancy))
  }
  override def toString = {
    var output = s"\tcav-id:${cav.id} | cost:$cost \n\t\t"
    schedule.sortWith(_.time < _.time).foreach { i =>
      output += s"${i}\n\t\t"
    }
    output
  }
}

object HouseholdCAVScheduling{
  def computeSkim(plans: List[BeamPlan]): Map[Coord, Map[Coord, Double]] = {
    var skim = Map[Coord, Map[Coord, Double]]()
    var activitySet = Set[Coord]()
    for (plan <- plans) {
      for (act <- plan.activities) {
        activitySet += act.getCoord
      }
    }
    for (actSrc <- activitySet) {
      skim = skim + (actSrc -> Map[Coord, Double]())
      for (actDst <- activitySet) {
        //TODO replace with BEAM GeoUtils
        val travelTime: Double = CoordUtils.calcEuclideanDistance(actSrc, actDst) * 60
        skim = skim + (actSrc -> (skim(actSrc) ++ Map(actDst -> travelTime)))
      }
    }
    skim
  }
}
