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
  override def toString =
    s"${person match {
      case Some(x) => x.getId
      case None    => "None"
    }}|${coord}|${time}|${delta_time}"
}

// Mobility Service Request is either a pickup or a dropoff
class MSRPickup(val person: Option[Person], val coord: Coord, val time: Double, val delta_time: Double)
    extends MobilityServiceRequest

class MSRDropoff(val person: Option[Person], val coord: Coord, val time: Double, val delta_time: Double)
    extends MobilityServiceRequest

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
  requests.sortWith(_.time < _.time)
  def apply(): List[MobilityServiceRequest] = { requests }
  override def toString = s"${requests}"
}

class HouseholdCAVPlanning(
  val plans: ArrayBuffer[BeamPlan],
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
      var new_cav_schedule: Option[CAVSchedule] = None
      if (request.isInstanceOf[MSRPickup]) {
        if (occupancy == cav.max_occupancy || math.abs(new_delta_time) > time_window) {
          println(s"Failed to pickup => ${request} | ${new_delta_time} | ${occupancy}")
        } else {
          val new_request = new MSRPickup(request.person, request.coord, request.time, new_delta_time)
          new_cav_schedule = Some(CAVSchedule(schedule :+ new_request, cost + new_delta_time, occupancy + 1, cav))
        }
      } else if (request.isInstanceOf[MSRDropoff]) {
        val index = schedule.lastIndexWhere(_.person == request.person)
        if (index < 0 || !schedule(index).isInstanceOf[MSRPickup]) {
          println(s"!!! Not supposed to dropoff before pickup => ${request} | ${new_delta_time} | ${occupancy}")
        } else if (math.abs(new_delta_time) > time_window) {
          println("unfeasible dropoff. Schedule will be dropped => ${request} | ${new_delta_time} | ${occupancy}")
          feasible = false
        } else {
          val new_request = new MSRDropoff(request.person, request.coord, request.time, new_delta_time)
          new_cav_schedule = Some(CAVSchedule(schedule :+ new_request, cost + new_delta_time, occupancy - 1, cav))
          feasible = false
        }
      } else {
        println(s"Neither a pickup nor a dropoff => ${request}")
      }
      new_cav_schedule
    }
    override def toString = {
      var output = s"\tcav-id:${cav.id} | cost:$cost | occupancy:$occupancy\n\t\t"
      for (i <- schedule) {
        output += s"${i}\n\t\t"
      }
      output
    }
  }

  case class HouseholdCAVSchedule(val cav_fleet_schedule: ArrayBuffer[CAVSchedule], val cost: Double)
      extends Ordered[HouseholdCAVSchedule] {
    var feasible: Boolean = true
    override def compare(that: HouseholdCAVSchedule): Int = { Ordering.Double.compare(this.cost, that.cost) }

    def check(request: MobilityServiceRequest): ArrayBuffer[HouseholdCAVSchedule] = {
      val new_household_schedule = new ArrayBuffer[HouseholdCAVSchedule]()
      scala.util.control.Breaks.breakable {
        for (cav_schedule <- cav_fleet_schedule) {
          cav_schedule
            .check(request)
            .foreach(
              x =>
                new_household_schedule += HouseholdCAVSchedule(
                  ArrayBuffer[CAVSchedule](x) ++= (cav_fleet_schedule - cav_schedule),
                  cost + x.cost
              )
            )
          feasible = feasible && cav_schedule.feasible
        }
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

  def apply(fleet_size: Int): List[HouseholdCAVSchedule] = {

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
          List[MobilityServiceRequest](new MobilityServiceRequest {
            def person: Option[Person] = None
            def coord: Coord = copyCoord(household_requests.requests.head.coord)
            def time: Double = household_requests.requests.head.time - 1 // so to be ranked before end time
            def delta_time: Double = 1 // to recover the subtracted second
          }),
          0,
          0,
          x
      ) // initial Cost and Occupancy
    )
    feasible_schedules = feasible_schedules :+ HouseholdCAVSchedule(empty_fleet_schedule, 0)

    // extract all possible schedule combinations
    for (request <- household_requests()) {
      var household_schedules_to_add = new ArrayBuffer[HouseholdCAVSchedule]
      var household_schedules_to_delete = new ArrayBuffer[HouseholdCAVSchedule]
      for (schedule <- feasible_schedules) {
        household_schedules_to_add ++= schedule.check(request)
        if(!schedule.feasible) {household_schedules_to_delete += schedule}
      }
      feasible_schedules = feasible_schedules.diff(household_schedules_to_delete) ++ household_schedules_to_add
    }
    feasible_schedules
  }

}

object Demo {

  def main(args: Array[String]): Unit = {

    val plans = scenario1
    val time_window = 10 * 3600
    val skim = computeSkim(plans)
    //println(plans)
    //println(skim)
    val algo = new HouseholdCAVPlanning(plans, time_window, skim)
    for (i <- algo(1).sortWith(_.cost < _.cost)) {
      println(i)
    }
  }

  def scenario1: ArrayBuffer[BeamPlan] = {
    val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
    val P: Person = population.getFactory.createPerson(Id.createPersonId("P1"))
    val HOME_COORD = new Coord(0, 0)
    val H11: Activity = PopulationUtils.createActivityFromCoord("H", HOME_COORD)
    H11.setEndTime(8 * 3600)
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

  def computeSkim(plans: ArrayBuffer[BeamPlan]): Map[Coord, Map[Coord, Double]] = {
    val skim = Map[Coord, Map[Coord, Double]]()
    for (plan <- plans) {
      for (act_src <- plan.activities) {
        skim(act_src.getCoord) = Map[Coord, Double]()
        for (act_dst <- plan.activities) {
          skim(act_src.getCoord)(act_dst.getCoord) =
            CoordUtils.calcEuclideanDistance(act_src.getCoord, act_dst.getCoord)
        }
      }
    }
    skim
  }
}
