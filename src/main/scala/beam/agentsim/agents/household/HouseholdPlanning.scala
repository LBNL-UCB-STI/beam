package beam.agentsim.agents.household
import beam.agentsim.agents.planning.BeamPlan
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.Id

import scala.collection.mutable.ArrayBuffer
import org.matsim.api.core.v01.population._
import org.matsim.core.config.ConfigUtils
import org.matsim.core.population.PopulationUtils
import org.matsim.core.utils.geometry.CoordUtils

import scala.collection.mutable.Map
import scala.collection.immutable.List

abstract class MobilityServiceRequest extends Ordered[MobilityServiceRequest] {
  def person: Option[Person]
  def coord: Coord
  def time: Double
  def delta_time: Double
  def getRequestTime(): Unit = { return (time) }
  def getServiceTime(): Unit = { return (time + delta_time) }
  def compare(that: MobilityServiceRequest) = { Ordering.Double.compare(this.time, that.time) }
}

// Mobility Service Request is either a pickup or a dropoff
class MSRPickup(val person: Option[Person], val coord: Coord, val time: Double, val delta_time: Double)
    extends MobilityServiceRequest {
  override def toString =
    s"Pickup { ${person match {
      case Some(x) => x.getId
      case None    => "NA"
    }}|${coord}|${time}|${delta_time} }"
}

class MSRDropoff(val person: Option[Person], val coord: Coord, val time: Double, val delta_time: Double)
    extends MobilityServiceRequest {
  override def toString =
    s"Dropoff { ${person match {
      case Some(x) => x.getId
      case None    => "NA"
    }}|${coord}|${time}|${delta_time} }"
}

class MSRRelocation(val person: Option[Person], val coord: Coord, val time: Double, val delta_time: Double)
    extends MobilityServiceRequest {
  override def toString =
    s"Relocation { ${person match {
      case Some(x) => x.getId
      case None    => "NA"
    }}|${coord}|${time}|${delta_time} }"
}

class HouseholdPlansToMSR(plans: ArrayBuffer[BeamPlan]) {
  var requests = List[MobilityServiceRequest]()
  for (plan <- plans) {
    for (activity <- plan.activities) {
      if (!activity.getStartTime.isInfinity && !activity.getStartTime.isNaN)
        requests = requests :+ new MSRDropoff(Some(plan.getPerson), activity.getCoord, activity.getStartTime, 0.0)
      if (!activity.getEndTime.isInfinity && !activity.getEndTime.isNaN)
        requests = requests :+ new MSRPickup(Some(plan.getPerson), activity.getCoord, activity.getEndTime, 0.0)
    }
  }
  requests = requests.sortWith(_.time < _.time)
  def apply(): List[MobilityServiceRequest] = { requests }
  override def toString = s"${requests}"
}

class HouseholdCAVPlanning(
  val plans: ArrayBuffer[BeamPlan],
  val fleet_size: Int,
  val time_window: Double,
  val skim: Map[Coord, Map[Coord, Double]]
) {

  private val fleet = ArrayBuffer[HouseholdCAV]()
  private var feasible_schedules = List[HouseholdCAVSchedule]()

  case class HouseholdCAV(id: Int, max_occupancy: Int)

  case class CAVSchedule(schedule: List[MobilityServiceRequest], cost: Double, occupancy: Int, cav: HouseholdCAV) {
    var feasible: Boolean = true

    def check(request: MobilityServiceRequest): Option[CAVSchedule] = {
      val travel_time = skim(schedule.last.coord)(request.coord)
      val arrival_time = schedule.last.time + schedule.last.delta_time + travel_time
      val new_delta_time = arrival_time - request.time
      val new_cost = cost + new_delta_time
      var new_cav_schedule: Option[CAVSchedule] = None
      if (request.isInstanceOf[MSRPickup]) {
        if (occupancy == cav.max_occupancy) {
          println(s"Vehicle capacity limit, not possible to pickup=> ${request} | ${new_delta_time} | ${occupancy}")
        } else if (occupancy != 0 && math.abs(new_delta_time) > time_window) {
          println(s"Unfeasible timing, not possible to pickup=> ${request} | ${new_delta_time} | ${occupancy}")
        } else if (occupancy == 0 && math.abs(new_delta_time) > time_window) {
          // *** THIS IS A RELOCATION TASK
          // NOT SUPPOSED TO BE HARD CODED HERE, SINCE IT DEPENDS ON THE OP MODEL
          val relocation_request = new MSRRelocation(None, request.coord, request.time - 1, new_delta_time)
          val new_request = new MSRPickup(request.person, request.coord, request.time, 0)
          new_cav_schedule = Some(
            CAVSchedule(schedule :+ relocation_request :+ new_request, new_cost, occupancy + 1, cav)
          )
        } else {
          val new_request = new MSRPickup(request.person, request.coord, request.time, new_delta_time)
          new_cav_schedule = Some(CAVSchedule(schedule :+ new_request, new_cost, occupancy + 1, cav))
        }
      } else if (request.isInstanceOf[MSRDropoff]) {
        val index = schedule.lastIndexWhere(_.person == request.person)
        if (index < 0 || !schedule(index).isInstanceOf[MSRPickup]) {
          // no dropoff without picking up
          //println(s"!!! Not supposed to dropoff before pickup => ${request} | ${new_delta_time} | ${occupancy}")
        } else if (math.abs(new_delta_time) > time_window) {
          println(s"unfeasible dropoff. Schedule will be dropped => ${request} | ${new_delta_time} | ${occupancy}")
          feasible = false
        } else {
          val new_request = new MSRDropoff(request.person, request.coord, request.time, new_delta_time)
          new_cav_schedule = Some(CAVSchedule(schedule :+ new_request, new_cost, occupancy - 1, cav))
          feasible = false
        }
      } else {
        println(s"Neither a pickup nor a dropoff => ${request}")
      }
      new_cav_schedule
    }
    override def toString = {
      var output = s"\tcav-id:${cav.id} | cost:$cost | occupancy:$occupancy\n\t\t"
      for (i <- schedule.sortWith(_.time < _.time)) {
        output += s"${i}\n\t\t"
      }
      output
    }
  }

  case class HouseholdCAVSchedule(val cav_fleet_schedule: ArrayBuffer[CAVSchedule])
      extends Ordered[HouseholdCAVSchedule] {
    var feasible: Boolean = true
    var cost: Double = 0
    cav_fleet_schedule.foreach(x => cost += x.cost)
    override def compare(that: HouseholdCAVSchedule): Int = { Ordering.Double.compare(this.cost, that.cost) }

    def check(request: MobilityServiceRequest): ArrayBuffer[HouseholdCAVSchedule] = {
      val new_household_schedule = new ArrayBuffer[HouseholdCAVSchedule]()
      for (cav_schedule <- cav_fleet_schedule) {
        cav_schedule.check(request) match {
          case Some(x) =>
            new_household_schedule += HouseholdCAVSchedule((cav_fleet_schedule - cav_schedule) :+ x)
          case None => //Nothing to do here
        }
        feasible = feasible && cav_schedule.feasible
      }
      new_household_schedule
    }
    override def toString = {
      var output = s"Household Schedule - COST:${cost}.\n"
      for (i <- cav_fleet_schedule) {
        output += s"${i}\n"
      }
      output
    }
  }

  private def copyCoord(c: Coord): Coord = {
    new Coord(c.getX, c.getY)
  }

  def apply(): List[HouseholdCAVSchedule] = {

    // extract potential household CAV requests from plans
    val household_requests = new HouseholdPlansToMSR(plans);

    // set up the household fleet
    for (i <- 0 until fleet_size) {
      fleet += HouseholdCAV(i, 4) //TODO probably to be extracted from Attr Map
    }

    // deploy the fleet or set up the initial household schedule
    var empty_fleet_schedule = ArrayBuffer[CAVSchedule]()
    fleet.foreach(
      x =>
        empty_fleet_schedule += CAVSchedule(
          List[MobilityServiceRequest](
            new MSRRelocation(
              None,
              copyCoord(household_requests.requests.head.coord),
              household_requests.requests.head.time - 1,
              1
            )
          ),
          0,
          0,
          x
      ) // initial Cost and Occupancy
    )
    feasible_schedules = feasible_schedules :+ HouseholdCAVSchedule(empty_fleet_schedule)

    // extract all possible schedule combinations
    for (request <- household_requests()) {
      var household_schedules_to_add = new ArrayBuffer[HouseholdCAVSchedule]
      var household_schedules_to_delete = new ArrayBuffer[HouseholdCAVSchedule]
      for (schedule <- feasible_schedules) {
        household_schedules_to_add ++= schedule.check(request)
        if (!schedule.feasible) { household_schedules_to_delete += schedule }
      }
      feasible_schedules = feasible_schedules.diff(household_schedules_to_delete) ++ household_schedules_to_add
    }
    feasible_schedules
  }

}

// ************
// MAIN

object Demo {

  def main(args: Array[String]): Unit = {

    val plans = scenario2
    val time_window = 15 * 60
    val skim = computeSkim(plans)
    //println(plans)
    printSkim(skim)
    val algo = new HouseholdCAVPlanning(plans, 1, time_window, skim)
    for (i <- algo().sortWith(_.cost < _.cost)) {
      println(i)
    }
  }

  def scenario1: ArrayBuffer[BeamPlan] = {
    val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
    val P: Person = population.getFactory.createPerson(Id.createPersonId("P1"))
    val HOME_COORD = new Coord(0, 0)
    val H11: Activity = PopulationUtils.createActivityFromCoord("H", HOME_COORD)
    H11.setEndTime(8.5 * 3600)
    val W1: Activity = PopulationUtils.createActivityFromCoord("W", new Coord(30, 0))
    W1.setStartTime(9 * 3600)
    W1.setEndTime(17 * 3600)
    val H12: Activity = PopulationUtils.createActivityFromCoord("H", HOME_COORD)
    H12.setStartTime(17.5 * 3600)
    val plan: Plan = population.getFactory.createPlan()
    plan.setPerson(P)
    plan.addActivity(H11)
    plan.addActivity(W1)
    plan.addActivity(H12)
    ArrayBuffer[BeamPlan](BeamPlan(plan))
  }

  def scenario2: ArrayBuffer[BeamPlan] = {
    val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
    val HOME_COORD = new Coord(0, 0)

    val P1: Person = population.getFactory.createPerson(Id.createPersonId("P1"))
    val H11: Activity = PopulationUtils.createActivityFromCoord("H", HOME_COORD)
    H11.setEndTime(8.5 * 3600)
    val W1: Activity = PopulationUtils.createActivityFromCoord("W", new Coord(30, 0))
    W1.setStartTime(9 * 3600)
    W1.setEndTime(17 * 3600)
    val H12: Activity = PopulationUtils.createActivityFromCoord("H", HOME_COORD)
    H12.setStartTime(17.5 * 3600)
    val plan1: Plan = population.getFactory.createPlan()
    plan1.setPerson(P1)
    plan1.addActivity(H11)
    plan1.addActivity(W1)
    plan1.addActivity(H12)

    val P2: Person = population.getFactory.createPerson(Id.createPersonId("P2"))
    val H21: Activity = PopulationUtils.createActivityFromCoord("H", HOME_COORD)
    H21.setEndTime(8.5 * 3600)
    val W2: Activity = PopulationUtils.createActivityFromCoord("W", new Coord(30, 10))
    W2.setStartTime(9 * 3600)
    W2.setEndTime(17 * 3600)
    val H22: Activity = PopulationUtils.createActivityFromCoord("H", HOME_COORD)
    H22.setStartTime(17.5 * 3600)
    val plan2: Plan = population.getFactory.createPlan()
    plan2.setPerson(P2)
    plan2.addActivity(H21)
    plan2.addActivity(W2)
    plan2.addActivity(H22)

    ArrayBuffer[BeamPlan](BeamPlan(plan1), BeamPlan(plan2))
  }

  def computeSkim(plans: ArrayBuffer[BeamPlan]): Map[Coord, Map[Coord, Double]] = {
    val skim = Map[Coord, Map[Coord, Double]]()
    var activity_set = Set[Coord]()
    for (plan <- plans) {
      for (act <- plan.activities) {
        activity_set += act.getCoord
      }
    }

    for (act_src <- activity_set) {
      skim(act_src) = Map[Coord, Double]()
      for (act_dst <- activity_set) {
        skim(act_src)(act_dst) = CoordUtils.calcEuclideanDistance(act_src, act_dst) * 60
      }
    }

    skim
  }

  def printSkim(skim: Map[Coord, Map[Coord, Double]]): Unit = {
    for (row <- skim.keySet) {
      print(s"${row}\t")
      for (col <- skim(row).keySet) {
        print(s"${skim(row)(col)}\t")
      }
    }
    println("")
  }
}
