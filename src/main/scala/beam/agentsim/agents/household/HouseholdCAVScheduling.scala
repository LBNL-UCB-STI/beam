package beam.agentsim.agents.household
import beam.agentsim.agents.planning.BeamPlan
import beam.agentsim.agents.vehicles.BeamVehicle
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
  person: Option[Person],
  activity: Activity,
  time: Double,
  deltaTime: Double,
  tag: MobilityServiceRequestType
) {
  override def toString =
    s"$tag{ ${person match {
      case Some(x) => x.getId
      case None    => "NA"
    }}|${activity.getType}|${(time / 3600).toInt}:${((time % 3600) / 60).toInt}:${(time % 60).toInt}|ocp:$deltaTime }"
}

class HouseholdPlansToMSR(plans: List[BeamPlan]) {
  var requests = List[MobilityServiceRequest]()
  for (plan <- plans) {
    for (activity <- plan.activities) {
      if (!activity.getStartTime.isInfinity && !activity.getStartTime.isNaN)
        requests = requests :+ new MobilityServiceRequest(
          Some(plan.getPerson),
          activity,
          activity.getStartTime,
          0.0,
          Dropoff
        )
      if (!activity.getEndTime.isInfinity && !activity.getEndTime.isNaN)
        requests = requests :+ new MobilityServiceRequest(
          Some(plan.getPerson),
          activity,
          activity.getEndTime,
          0.0,
          Pickup
        )
    }
  }
  requests = requests.sortWith(_.time < _.time)
  def apply(): List[MobilityServiceRequest] = { requests }
  override def toString = s"${requests}"
}

class HouseholdCAVScheduling(
  plans: List[BeamPlan],
  cavVehicles: List[BeamVehicle],
  timeWindow: Double,
  skim: Map[Coord, Map[Coord, Double]]
) {
  private var fleet = cavVehicles.map(veh => HouseholdCAV(veh.id,veh.beamVehicleType.seatingCapacity))
  private var feasibleSchedules = List[HouseholdSchedule]()

  case class HouseholdCAV(id: Id[BeamVehicle], maxOccupancy: Int)

  class CAVSchedule(val schedule: List[MobilityServiceRequest], val cav: HouseholdCAV, val cost: Double, val occupancy: Int) {
    var feasible: Boolean = true

    def check(request: MobilityServiceRequest): Option[CAVSchedule] = {
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
            val newRequest = new MobilityServiceRequest(request.person, request.activity, request.time, 0, Pickup)
            newCavSchedule = Some(
              new CAVSchedule(schedule :+ relocationRequest :+ newRequest, cav, newCost, occupancy + 1)
            )
          } else if (occupancy < cav.maxOccupancy && math.abs(newDeltaTime) <= timeWindow) {
            val newRequest =
              new MobilityServiceRequest(request.person, request.activity, request.time, newDeltaTime, Pickup)
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
    override def toString = {
      var output = s"\tcav-id:${cav.id} | cost:$cost \n\t\t"
      schedule.sortWith(_.time < _.time).foreach { i =>
        output += s"${i}\n\t\t"
      }
      output
    }
  }

  class HouseholdSchedule(val cavFleetSchedule: List[CAVSchedule]) {
    var feasible: Boolean = true
    var cost: Double = 0
    cavFleetSchedule.foreach { x =>
      cost += x.cost
    }

    def check(request: MobilityServiceRequest): List[HouseholdSchedule] = {
      var newHouseholdSchedule = List[HouseholdSchedule]()
      for (cavSchedule <- cavFleetSchedule) {
        cavSchedule.check(request) match {
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

  def apply(): List[HouseholdSchedule] = {

    // extract potential household CAV requests from plans
    val householdRequests = new HouseholdPlansToMSR(plans);

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
    for (request <- householdRequests()) {
      var householdSchedulesToAdd = List[HouseholdSchedule]()
      var householdSchedulesToDelete = List[HouseholdSchedule]()
      for (schedule <- feasibleSchedules) {
        householdSchedulesToAdd ++= schedule.check(request)
        if (!schedule.feasible) {
          householdSchedulesToDelete = householdSchedulesToDelete :+ schedule
        }
      }
      feasibleSchedules = feasibleSchedules.diff(householdSchedulesToDelete) ++ householdSchedulesToAdd
    }
    feasibleSchedules
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
