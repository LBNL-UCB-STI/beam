package beam.agentsim.agents.household
import beam.agentsim.agents.planning.BeamPlan
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.Id

import org.matsim.api.core.v01.population._
import org.matsim.core.config.ConfigUtils
import org.matsim.core.population.PopulationUtils
import org.matsim.core.utils.geometry.CoordUtils

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

case class HouseholdCAVScheduling(
  plans: List[BeamPlan],
  fleetSize: Int,
  timeWindow: Double,
  skim: Map[Coord, Map[Coord, Double]]
) {
  private var fleet = List[HouseholdCAV]()
  private var feasibleSchedules = List[HouseholdSchedule]()

  case class HouseholdCAV(id: Int, maxOccupancy: Int)

  case class CAVSchedule(schedule: List[MobilityServiceRequest], cav: HouseholdCAV, cost: Double, occupancy: Int) {
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
              CAVSchedule(schedule :+ relocationRequest :+ newRequest, cav, newCost, occupancy + 1)
            )
          } else if (occupancy < cav.maxOccupancy && math.abs(newDeltaTime) <= timeWindow) {
            val newRequest =
              new MobilityServiceRequest(request.person, request.activity, request.time, newDeltaTime, MSRPickup)
            newCavSchedule = Some(CAVSchedule(schedule :+ newRequest, cav, newCost, occupancy + 1))
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
            newCavSchedule = Some(CAVSchedule(schedule :+ newRequest, cav, newCost, occupancy - 1))
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

  case class HouseholdSchedule(val cavFleetSchedule: List[CAVSchedule]) {
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
            newHouseholdSchedule = newHouseholdSchedule :+ HouseholdSchedule(
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
        emptyFleetSchedule = emptyFleetSchedule :+ CAVSchedule(
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
    feasibleSchedules = feasibleSchedules :+ HouseholdSchedule(emptyFleetSchedule)

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

// ***********************************
// MAIN
// ***********************************

object Demo {

  def main(args: Array[String]): Unit = {
    var tot = 0
    val t0 = System.nanoTime()
    for (i <- 0 until 1) {
      val plans = scenario1(s"$i")
      val timeWindow = 15 * 60
      val skim = computeSkim(plans)
      //println(plans)
      //printSkim(skim)
      val algo = new HouseholdCAVScheduling(plans, 1, timeWindow, skim)
      val schedules = algo().sortWith(_.cost < _.cost)
      tot += schedules.size
//      println(s"iteration $i - # combination ${schedules.size}")
//      for (j <- schedules) {
//        println(j)
//      }
    }
    val t1 = System.nanoTime()
    println(s"Elapsed time: ${(t1 - t0) / 1.0E9} seconds - number of objects: $tot")
  }

  def scenario1(householdId: String): List[BeamPlan] = {
    val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
    val P: Person = population.getFactory.createPerson(Id.createPersonId("p-" + householdId + "-1"))
    val homeCoord = new Coord(0, 0)
    val H11: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H11.setEndTime(8.5 * 3600)
    val W1: Activity = PopulationUtils.createActivityFromCoord("work", new Coord(30, 0))
    W1.setStartTime(9 * 3600)
    W1.setEndTime(17 * 3600)
    val H12: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H12.setStartTime(17.5 * 3600)
    val plan: Plan = population.getFactory.createPlan()
    plan.setPerson(P)
    plan.addActivity(H11)
    plan.addActivity(W1)
    plan.addActivity(H12)
    List[BeamPlan](BeamPlan(plan))
  }

  def scenario2(householdId: String): List[BeamPlan] = {
    val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
    val homeCoord = new Coord(0, 0)

    val P1: Person = population.getFactory.createPerson(Id.createPersonId("P_" + householdId + "_1"))
    val H11: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H11.setEndTime(8.5 * 3600)
    val W1: Activity = PopulationUtils.createActivityFromCoord("work1", new Coord(30, 0))
    W1.setStartTime(9 * 3600)
    W1.setEndTime(17 * 3600)
    val H12: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H12.setStartTime(17.5 * 3600)
    val plan1: Plan = population.getFactory.createPlan()
    plan1.setPerson(P1)
    plan1.addActivity(H11)
    plan1.addActivity(W1)
    plan1.addActivity(H12)

    val P2: Person = population.getFactory.createPerson(Id.createPersonId("p-" + householdId + "-2"))
    val H21: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H21.setEndTime(8.5 * 3600)
    val W2: Activity = PopulationUtils.createActivityFromCoord("work2", new Coord(30, 10))
    W2.setStartTime(9 * 3600)
    W2.setEndTime(17 * 3600)
    val H22: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H22.setStartTime(17.5 * 3600)
    val plan2: Plan = population.getFactory.createPlan()
    plan2.setPerson(P2)
    plan2.addActivity(H21)
    plan2.addActivity(W2)
    plan2.addActivity(H22)

    List[BeamPlan](BeamPlan(plan1), BeamPlan(plan2))
  }

  def scenario3(householdId: String): List[BeamPlan] = {
    val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
    val homeCoord = new Coord(0, 0)

    val P1: Person = population.getFactory.createPerson(Id.createPersonId("p-" + householdId + "-1"))
    val H11: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H11.setEndTime(9 * 3600)
    val W1: Activity = PopulationUtils.createActivityFromCoord("work1", new Coord(60, 0))
    W1.setStartTime(10 * 3600)
    W1.setEndTime(19.5 * 3600)
    val H12: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H12.setStartTime(20.5 * 3600)
    val plan1: Plan = population.getFactory.createPlan()
    plan1.setPerson(P1)
    plan1.addActivity(H11)
    plan1.addActivity(W1)
    plan1.addActivity(H12)

    val P2: Person = population.getFactory.createPerson(Id.createPersonId("P-" + householdId + "-2"))
    val H21: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H21.setEndTime(7.5 * 3600)
    val W2: Activity = PopulationUtils.createActivityFromCoord("work2", new Coord(10, 40))
    W2.setStartTime(8.5 * 3600)
    W2.setEndTime(16 * 3600)
    val Sh21: Activity = PopulationUtils.createActivityFromCoord("shop", new Coord(10, 0))
    Sh21.setStartTime(17 * 3600)
    Sh21.setEndTime(19 * 3600)
    val H22: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H22.setStartTime(19.5 * 3600)
    val plan2: Plan = population.getFactory.createPlan()
    plan2.setPerson(P2)
    plan2.addActivity(H21)
    plan2.addActivity(W2)
    plan2.addActivity(Sh21)
    plan2.addActivity(H22)

    val schoolCoord = new Coord(0, 10)
    val P3: Person = population.getFactory.createPerson(Id.createPersonId("p-" + householdId + "-3"))
    val H31: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H31.setEndTime(7.5 * 3600)
    val Sc3: Activity = PopulationUtils.createActivityFromCoord("school1", schoolCoord)
    Sc3.setStartTime(8 * 3600)
    Sc3.setEndTime(16 * 3600)
    val H32: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H32.setStartTime(16.5 * 3600)
    val plan3: Plan = population.getFactory.createPlan()
    plan3.setPerson(P3)
    plan3.addActivity(H31)
    plan3.addActivity(Sc3)
    plan3.addActivity(H32)

    val P4: Person = population.getFactory.createPerson(Id.createPersonId("P_" + householdId + "_4"))
    val H41: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H41.setEndTime(7.5 * 3600)
    val Sc4: Activity = PopulationUtils.createActivityFromCoord("school2", schoolCoord)
    Sc4.setStartTime(8 * 3600)
    Sc4.setEndTime(16 * 3600)
    val H42: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H42.setStartTime(16.5 * 3600)
    val plan4: Plan = population.getFactory.createPlan()
    plan4.setPerson(P4)
    plan4.addActivity(H41)
    plan4.addActivity(Sc4)
    plan4.addActivity(H42)

    val P5: Person = population.getFactory.createPerson(Id.createPersonId("P_" + householdId + "_5"))
    val H51: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H51.setEndTime(8.5 * 3600)
    val Sc5: Activity = PopulationUtils.createActivityFromCoord("school3", new Coord(50, 10))
    Sc5.setStartTime(9.5 * 3600)
    Sc5.setEndTime(17 * 3600)
    val Ho5: Activity = PopulationUtils.createActivityFromCoord("leisure", new Coord(50, 0))
    Ho5.setStartTime(17.5 * 3600)
    Ho5.setEndTime(19.5 * 3600)
    val H52: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H52.setStartTime(20.5 * 3600)
    val plan5: Plan = population.getFactory.createPlan()
    plan5.setPerson(P5)
    plan5.addActivity(H51)
    plan5.addActivity(Sc5)
    plan5.addActivity(Ho5)
    plan5.addActivity(H52)

    List[BeamPlan](BeamPlan(plan1), BeamPlan(plan2), BeamPlan(plan3), BeamPlan(plan4), BeamPlan(plan5))
  }

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

  def printSkim(skim: Map[Coord, Map[Coord, Double]]): Unit = {
    for (row <- skim.keySet) {
      print(s"${row}\t")
      for (col <- skim(row).keySet) {
        print(s"${skim(row)(col)}\t")
      }
      println("")
    }
  }
}
