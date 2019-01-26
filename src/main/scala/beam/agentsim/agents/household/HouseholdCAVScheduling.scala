package beam.agentsim.agents.household
import beam.agentsim.agents.planning.BeamPlan
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.router.Modes.BeamMode
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.households.Household

import scala.collection.immutable.{List, Map}

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
  tag: MobilityServiceRequestType
) {
  override def toString =
    s"$tag{ ${person match {
      case Some(x) => x.toString
      case None    => "NA"
    }}|${activity.getType}|${(time / 3600).toInt}:${((time % 3600) / 60).toInt}:${(time % 60).toInt}|delta:$deltaTime }"
}

object DefaultMode {

  def get(legOption: Option[Leg], nbVehicles: Int): BeamMode = {
    // TODO: delete the code if the simulation does not break due a None in Option[Leg]
    val beamModeOption = legOption match {
      case Some(leg) => BeamMode.fromString(leg.getMode)
      case None      => None
    }

    // If the mode is undefined and no available household is available, agent will be considered as using pt as a base mode
    // If the default mode is car and no available household is available, agent will be still be considered using car as a base mode
    // TODO: an agent cannot drive a car if it does not have a driving license
    val defaultMode = beamModeOption match {
      case Some(beamMode) => beamMode
      case None           => if (nbVehicles <= 0) BeamMode.TRANSIT else BeamMode.CAR
    }

    defaultMode
  }
}

class HouseholdPlansToMSR(
  val householdPlans: List[BeamPlan],
  var householdNbOfVehicles: Int,
  val skim: Map[BeamMode, Map[Coord, Map[Coord, Double]]]
) {
  // cost = total travel times of all members of the household
  var cost: Double = 0

  var requests = List[MobilityServiceRequest]()
  householdPlans.foreach { plan =>
    var counter = householdNbOfVehicles
    var usedCar = false

    // iterating over trips and (1) compute cost (2) get pick up requests (3) get dropoff requests
    plan.trips.sliding(2).foreach { tripTuple =>
      val startActivity = tripTuple(0).activity
      val endActivity = tripTuple(1).activity
      val legTrip = tripTuple(1).leg
      val defaultMode = DefaultMode.get(legTrip, counter)
      val defaultTravelTime = skim(defaultMode)(startActivity.getCoord)(endActivity.getCoord)

      defaultMode match {
        case BeamMode.CAR => usedCar = true
        case _            => None
      }

      // increment cost
      cost += defaultTravelTime

      // identifying pickups
      requests = new MobilityServiceRequest(
        Some(plan.getPerson.getId),
        startActivity,
        startActivity.getEndTime,
        0.0,
        Pickup
      ) :: requests

      // identifying dropoffs
      requests = new MobilityServiceRequest(
        Some(plan.getPerson.getId),
        endActivity,
        startActivity.getEndTime + skim(defaultMode)(startActivity.getCoord)(endActivity.getCoord),
        0.0,
        Dropoff
      ) :: requests
    }

    // one less available vehicle if it is used by a member of the household
    if (usedCar) counter -= 1
  }

  requests = requests.sortWith(_.time < _.time)
  override def toString = s"${requests}"
}

class HouseholdCAVScheduling(
  val population: org.matsim.api.core.v01.population.Population,
  val household: Household,
  val cavVehicles: List[BeamVehicle],
  val timeWindow: Double
) {
  import beam.agentsim.agents.memberships.Memberships.RankedGroup._
  private implicit val pop: org.matsim.api.core.v01.population.Population = population
  private val householdPlans = household.members.map(person => BeamPlan(person.getSelectedPlan)).toList
  private val householdFleet = cavVehicles.map(veh => HouseholdCAV(veh.id, veh.beamVehicleType.seatingCapacity))
  private var feasibleSchedules = List[HouseholdSchedule]()
  private val skim = HouseholdCAVScheduling.computeSkim(householdPlans)
  // extract potential household CAV requests from plans
  val householdRequests = new HouseholdPlansToMSR(householdPlans, householdFleet.size, skim);

  case class HouseholdCAV(id: Id[BeamVehicle], maxOccupancy: Int)

  class CAVSchedule(
    val schedule: List[MobilityServiceRequest],
    val cav: HouseholdCAV,
    val cost: Double,
    val occupancy: Int
  ) {
    var feasible: Boolean = true

    def check(request: MobilityServiceRequest): Option[CAVSchedule] = {
      val travelTime = skim(BeamMode.CAR)(schedule.last.activity.getCoord)(request.activity.getCoord)
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

  // get k lowest scored schedules
  def apply(k: Int): List[HouseholdSchedule] = {

    // deploy the householdFleet or set up the initial household schedule
    var emptyFleetSchedule = List[CAVSchedule]()
    householdFleet.foreach(
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
        householdSchedulesToAdd ++= schedule.check(request)
        if (!schedule.feasible) {
          householdSchedulesToDelete = householdSchedulesToDelete :+ schedule
        }
      }
      feasibleSchedules = feasibleSchedules.diff(householdSchedulesToDelete) ++ householdSchedulesToAdd
    }
    return feasibleSchedules.sortWith(_.cost < _.cost).take(k)
  }

}

object HouseholdCAVScheduling {

  def computeSkim(plans: List[BeamPlan]): Map[BeamMode, Map[Coord, Map[Coord, Double]]] = {

    var skim: Map[BeamMode, Map[Coord, Map[Coord, Double]]] = Map(
      BeamMode.CAR     -> Map[Coord, Map[Coord, Double]](),
      BeamMode.TRANSIT -> Map[Coord, Map[Coord, Double]]()
    )

    var activitySet = Set[Coord]()
    for (plan <- plans) {
      for (act <- plan.activities) {
        activitySet += act.getCoord
      }
    }

    for (actSrc <- activitySet) {
      skim = Map(
        BeamMode.CAR     -> (skim(BeamMode.CAR) ++ Map(actSrc     -> Map[Coord, Double]())),
        BeamMode.TRANSIT -> (skim(BeamMode.TRANSIT) ++ Map(actSrc -> Map[Coord, Double]()))
      )
      for (actDst <- activitySet) {
        //TODO replace with BEAM GeoUtils
        val eucDistance: Double = CoordUtils.calcEuclideanDistance(actSrc, actDst)
        skim = Map(
          BeamMode.CAR -> (skim(BeamMode.CAR) ++ Map(
            actSrc -> (skim(BeamMode.CAR)(actSrc) ++ Map(actDst -> eucDistance * 60))
          )),
          BeamMode.TRANSIT -> (skim(BeamMode.TRANSIT) ++ Map(
            actSrc -> (skim(BeamMode.TRANSIT)(actSrc) ++ Map(actDst -> eucDistance * 120))
          ))
        )
      }
    }
    skim
  }
}
