package beam.agentsim.agents.household
import beam.agentsim.agents.planning.BeamPlan
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.population.{Activity, Person, Plan}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.population.PopulationUtils
import org.matsim.core.utils.geometry.CoordUtils
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.immutable.{List, Map}

class HouseholdCAVSchedulingTest extends FlatSpec with Matchers {

  behavior of "HouseholdCAVScheduling"

  it should "generate four plans with 4/6/3/1 requests each" in {
    val plans = scenario1()
    val timeWindow = 15 * 60
    val skim = computeSkim(plans)
    val algo = new HouseholdCAVScheduling(plans, 1, timeWindow, skim)
    val schedules = algo().sortWith(_.cost < _.cost)
    schedules should have length 4
    schedules.foreach { x =>
      x.cavFleetSchedule.foreach(
        y => y.schedule should (((have length 4 or have length 6) or have length 3) or have length 1)
      )
    }
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

  def scenario1(): List[BeamPlan] = {
    val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
    val P: Person = population.getFactory.createPerson(Id.createPersonId("p-0-1"))
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

//  def scenario2(householdId: String): List[BeamPlan] = {
//    val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
//    val homeCoord = new Coord(0, 0)
//
//    val P1: Person = population.getFactory.createPerson(Id.createPersonId("P_" + householdId + "_1"))
//    val H11: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
//    H11.setEndTime(8.5 * 3600)
//    val W1: Activity = PopulationUtils.createActivityFromCoord("work1", new Coord(30, 0))
//    W1.setStartTime(9 * 3600)
//    W1.setEndTime(17 * 3600)
//    val H12: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
//    H12.setStartTime(17.5 * 3600)
//    val plan1: Plan = population.getFactory.createPlan()
//    plan1.setPerson(P1)
//    plan1.addActivity(H11)
//    plan1.addActivity(W1)
//    plan1.addActivity(H12)
//
//    val P2: Person = population.getFactory.createPerson(Id.createPersonId("p-" + householdId + "-2"))
//    val H21: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
//    H21.setEndTime(8.5 * 3600)
//    val W2: Activity = PopulationUtils.createActivityFromCoord("work2", new Coord(30, 10))
//    W2.setStartTime(9 * 3600)
//    W2.setEndTime(17 * 3600)
//    val H22: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
//    H22.setStartTime(17.5 * 3600)
//    val plan2: Plan = population.getFactory.createPlan()
//    plan2.setPerson(P2)
//    plan2.addActivity(H21)
//    plan2.addActivity(W2)
//    plan2.addActivity(H22)
//
//    List[BeamPlan](BeamPlan(plan1), BeamPlan(plan2))
//  }
//
//  def scenario3(householdId: String): List[BeamPlan] = {
//    val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
//    val homeCoord = new Coord(0, 0)
//
//    val P1: Person = population.getFactory.createPerson(Id.createPersonId("p-" + householdId + "-1"))
//    val H11: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
//    H11.setEndTime(9 * 3600)
//    val W1: Activity = PopulationUtils.createActivityFromCoord("work1", new Coord(60, 0))
//    W1.setStartTime(10 * 3600)
//    W1.setEndTime(19.5 * 3600)
//    val H12: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
//    H12.setStartTime(20.5 * 3600)
//    val plan1: Plan = population.getFactory.createPlan()
//    plan1.setPerson(P1)
//    plan1.addActivity(H11)
//    plan1.addActivity(W1)
//    plan1.addActivity(H12)
//
//    val P2: Person = population.getFactory.createPerson(Id.createPersonId("P-" + householdId + "-2"))
//    val H21: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
//    H21.setEndTime(7.5 * 3600)
//    val W2: Activity = PopulationUtils.createActivityFromCoord("work2", new Coord(10, 40))
//    W2.setStartTime(8.5 * 3600)
//    W2.setEndTime(16 * 3600)
//    val Sh21: Activity = PopulationUtils.createActivityFromCoord("shop", new Coord(10, 0))
//    Sh21.setStartTime(17 * 3600)
//    Sh21.setEndTime(19 * 3600)
//    val H22: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
//    H22.setStartTime(19.5 * 3600)
//    val plan2: Plan = population.getFactory.createPlan()
//    plan2.setPerson(P2)
//    plan2.addActivity(H21)
//    plan2.addActivity(W2)
//    plan2.addActivity(Sh21)
//    plan2.addActivity(H22)
//
//    val schoolCoord = new Coord(0, 10)
//    val P3: Person = population.getFactory.createPerson(Id.createPersonId("p-" + householdId + "-3"))
//    val H31: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
//    H31.setEndTime(7.5 * 3600)
//    val Sc3: Activity = PopulationUtils.createActivityFromCoord("school1", schoolCoord)
//    Sc3.setStartTime(8 * 3600)
//    Sc3.setEndTime(16 * 3600)
//    val H32: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
//    H32.setStartTime(16.5 * 3600)
//    val plan3: Plan = population.getFactory.createPlan()
//    plan3.setPerson(P3)
//    plan3.addActivity(H31)
//    plan3.addActivity(Sc3)
//    plan3.addActivity(H32)
//
//    val P4: Person = population.getFactory.createPerson(Id.createPersonId("P_" + householdId + "_4"))
//    val H41: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
//    H41.setEndTime(7.5 * 3600)
//    val Sc4: Activity = PopulationUtils.createActivityFromCoord("school2", schoolCoord)
//    Sc4.setStartTime(8 * 3600)
//    Sc4.setEndTime(16 * 3600)
//    val H42: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
//    H42.setStartTime(16.5 * 3600)
//    val plan4: Plan = population.getFactory.createPlan()
//    plan4.setPerson(P4)
//    plan4.addActivity(H41)
//    plan4.addActivity(Sc4)
//    plan4.addActivity(H42)
//
//    val P5: Person = population.getFactory.createPerson(Id.createPersonId("P_" + householdId + "_5"))
//    val H51: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
//    H51.setEndTime(8.5 * 3600)
//    val Sc5: Activity = PopulationUtils.createActivityFromCoord("school3", new Coord(50, 10))
//    Sc5.setStartTime(9.5 * 3600)
//    Sc5.setEndTime(17 * 3600)
//    val Ho5: Activity = PopulationUtils.createActivityFromCoord("leisure", new Coord(50, 0))
//    Ho5.setStartTime(17.5 * 3600)
//    Ho5.setEndTime(19.5 * 3600)
//    val H52: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
//    H52.setStartTime(20.5 * 3600)
//    val plan5: Plan = population.getFactory.createPlan()
//    plan5.setPerson(P5)
//    plan5.addActivity(H51)
//    plan5.addActivity(Sc5)
//    plan5.addActivity(Ho5)
//    plan5.addActivity(H52)
//
//    List[BeamPlan](BeamPlan(plan1), BeamPlan(plan2), BeamPlan(plan3), BeamPlan(plan4), BeamPlan(plan5))
//  }

}
