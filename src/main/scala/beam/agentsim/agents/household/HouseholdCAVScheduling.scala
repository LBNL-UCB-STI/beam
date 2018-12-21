package beam.agentsim.agents.household
import beam.agentsim.agents.planning.BeamPlan
import org.matsim.api.core.v01.Coord

import org.matsim.api.core.v01.population._

import scala.collection.immutable.Map
import scala.collection.immutable.List

sealed trait MobilityServiceRequestType
case object MSRPickup extends MobilityServiceRequestType
case object MSRDropoff extends MobilityServiceRequestType
case object MSRRelocation extends MobilityServiceRequestType
case object MSRInit extends MobilityServiceRequestType

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
          MSRDropoff
        )
      if (!activity.getEndTime.isInfinity && !activity.getEndTime.isNaN)
        requests = requests :+ new MobilityServiceRequest(
          Some(plan.getPerson),
          activity,
          activity.getEndTime,
          0.0,
          MSRPickup
        )
    }
  }
  requests = requests.sortWith(_.time < _.time)
  def apply(): List[MobilityServiceRequest] = { requests }
  override def toString = s"${requests}"
}

class HouseholdCAVScheduling(
  plans: List[BeamPlan],
  fleetSize: Int,
  timeWindow: Double,
  skim: Map[Coord, Map[Coord, Double]]
) {
  private var fleet = List[HouseholdCAV]()
  private var feasibleSchedules = List[HouseholdSchedule]()

  case class HouseholdCAV(id: Int, maxOccupancy: Int)

  class CAVSchedule(val schedule: List[MobilityServiceRequest], cav: HouseholdCAV, val cost: Double, occupancy: Int) {
    var feasible: Boolean = true

    def check(request: MobilityServiceRequest): Option[CAVSchedule] = {
      val travelTime = skim(schedule.last.activity.getCoord)(request.activity.getCoord)
      val arrivalTime = schedule.last.time + schedule.last.deltaTime + travelTime
      val newDeltaTime = arrivalTime - request.time
      val newCost = cost + newDeltaTime
      var newCavSchedule: Option[CAVSchedule] = None
      request.tag match {
        case MSRPickup =>
          if (occupancy == 0 && newDeltaTime < -1 * timeWindow) {
            val relocationRequest =
              new MobilityServiceRequest(None, request.activity, request.time - 1, newDeltaTime, MSRRelocation)
            val newRequest = new MobilityServiceRequest(request.person, request.activity, request.time, 0, MSRPickup)
            newCavSchedule = Some(
              new CAVSchedule(schedule :+ relocationRequest :+ newRequest, cav, newCost, occupancy + 1)
            )
          } else if (occupancy < cav.maxOccupancy && math.abs(newDeltaTime) <= timeWindow) {
            val newRequest =
              new MobilityServiceRequest(request.person, request.activity, request.time, newDeltaTime, MSRPickup)
            newCavSchedule = Some(new CAVSchedule(schedule :+ newRequest, cav, newCost, occupancy + 1))
          } else {
            // Dead End, Not going down this branch
          }
        case MSRDropoff =>
          val index = schedule.lastIndexWhere(_.person == request.person)
          if (index < 0 || schedule(index).tag != MSRPickup) {
            // Dead End, Not going down this branch
          } else if (math.abs(newDeltaTime) > timeWindow) {
            // Current Schedule unfeasible, to be marked for removal
            feasible = false
          } else {
            val newRequest =
              new MobilityServiceRequest(request.person, request.activity, request.time, newDeltaTime, MSRDropoff)
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

    // set up the household fleet
    for (i <- 0 until fleetSize) {
      fleet = fleet :+ HouseholdCAV(i, 4) //TODO probably to be extracted from Attr Map
    }

    // deploy the fleet or set up the initial household schedule
    var emptyFleetSchedule = List[CAVSchedule]()
    fleet.foreach(
      x =>
        emptyFleetSchedule = emptyFleetSchedule :+ new CAVSchedule(
          List[MobilityServiceRequest](
            new MobilityServiceRequest(
              None,
              householdRequests.requests.head.activity,
              householdRequests.requests.head.time - 1,
              1,
              MSRInit
            )
          ),
          x,
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
