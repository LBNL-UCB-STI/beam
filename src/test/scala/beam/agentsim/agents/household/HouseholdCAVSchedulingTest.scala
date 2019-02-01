package beam.agentsim.agents.household
import beam.agentsim.agents.planning.BeamPlan
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.FuelType.Gasoline
import beam.agentsim.agents.vehicles.VehicleCategory.Car
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.router.Modes.BeamMode
import org.matsim.api.core.v01.population.{Activity, Person, Plan}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.population.PopulationUtils
import org.matsim.core.population.io.PopulationReader
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.households.{Household, HouseholdsFactoryImpl}
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.immutable.{List, Map}
import scala.collection.{JavaConverters, mutable}

class HouseholdCAVSchedulingTest extends FlatSpec with Matchers {

  behavior of "HouseholdCAVScheduling"
  val defaultCAVBeamVehicleType = BeamVehicleType(Id.create("CAV-TYPE-DEFAULT", classOf[BeamVehicleType]),
    4,
    0,
    4.5,
    Gasoline,
    3656.0,
    3655980000.0,
    None,
    vehicleCategory = Car,
    automationLevel = 5)

  it should "generate four schedules with 4,6,3 & 1 requests each" in {
    val config = ConfigUtils.createConfig()
    implicit val sc: org.matsim.api.core.v01.Scenario =
      ScenarioUtils.createScenario(config)

    val cavs = List[BeamVehicle](
      new BeamVehicle(Id.createVehicleId("id1"), new Powertrain(0.0), defaultCAVBeamVehicleType)
    )
    val household: Household = scenario1(cavs)
    val skim = getSkim(sc, household)

    val algo = new HouseholdCAVScheduling(sc.getPopulation, household, cavs, 2, 2, skim)
    val schedules = algo.getAllSchedules
    schedules should have length 4
    schedules.foreach { x =>
      x.cavFleetSchedule should have length 1
      x.cavFleetSchedule(0).schedule should (((have length 4 or have length 6) or have length 3) or have length 1)
    }
    println(s"*** scenario 1 *** ${schedules.size} combinations")
    println(schedules)
  }

  it should "generate on schedule with only the pooling version of two passengers" in {
    val config = ConfigUtils.createConfig()
    implicit val sc: org.matsim.api.core.v01.Scenario =
      ScenarioUtils.createScenario(config)

    val vehicles = List[BeamVehicle](
      new BeamVehicle(Id.createVehicleId("id1"), new Powertrain(0.0), defaultCAVBeamVehicleType),
      new BeamVehicle(Id.createVehicleId("id2"), new Powertrain(0.0), BeamVehicleType.defaultCarBeamVehicleType)
    )
    val household: Household = scenario2(vehicles)
    val skim = getSkim(sc, household)

    val algo = new HouseholdCAVScheduling(sc.getPopulation, household, vehicles, 10*60, 15*60, skim)
    val schedule = algo.getMostProductiveCAVSchedule

    schedule.cavFleetSchedule should have length 1
    schedule.cavFleetSchedule(0).schedule should have length 10
    schedule.cavFleetSchedule(0).schedule.filter(_.person.isDefined).groupBy(_.person).mapValues(_.size).foldLeft(0)(_+_._2) shouldBe 8

    println(s"*** scenario 2 *** ")
    println(schedule)
  }


  it should "generate one schedules pooling in morning and separate trips in the evening" in {
    val config = ConfigUtils.createConfig()
    implicit val sc: org.matsim.api.core.v01.Scenario =
      ScenarioUtils.createScenario(config)

    val vehicles = List[BeamVehicle](
      new BeamVehicle(Id.createVehicleId("id1"), new Powertrain(0.0), defaultCAVBeamVehicleType),
      new BeamVehicle(Id.createVehicleId("id2"), new Powertrain(0.0), BeamVehicleType.defaultCarBeamVehicleType)
    )
    val household: Household = scenario3(vehicles)
    val skim = getSkim(sc, household)

    val algo = new HouseholdCAVScheduling(sc.getPopulation, household, vehicles, 10*60, 15*60, skim)
    val schedule = algo.getMostProductiveCAVSchedule
    schedule.cavFleetSchedule should have length 1
    schedule.cavFleetSchedule(0).schedule should have length 10
    schedule.cavFleetSchedule(0).schedule.filter(_.person.isDefined).groupBy(_.person).mapValues(_.size).foldLeft(0)(_+_._2) shouldBe 8
    println(s"*** scenario 3 ***")
    println(schedule)
  }

  it should "generate four thousands and ninety six schedules" in {
    val config = ConfigUtils.createConfig()
    implicit val sc: org.matsim.api.core.v01.Scenario =
      ScenarioUtils.createScenario(config)

    val vehicles = List[BeamVehicle](
      new BeamVehicle(Id.createVehicleId("id1"), new Powertrain(0.0), defaultCAVBeamVehicleType)
    )
    val household: Household = scenario4(vehicles)

    val skim = getSkim(sc, household)

    val algo = new HouseholdCAVScheduling(sc.getPopulation, household, vehicles, 10*60, 15*60, skim)
    val schedules = algo.getMostProductiveCAVSchedule()
    //schedules should have length 4096
    println(s"*** scenario 4 *** ")
    println(schedules)
  }

  // ******************
  // Scenarios
  // ******************
  def scenario1(vehicles: List[BeamVehicle])(implicit sc: org.matsim.api.core.v01.Scenario): Household = {
    val pop = sc.getPopulation
    val household = new HouseholdsFactoryImpl().createHousehold(Id.create("dummy", classOf[Household]))

    val p: Person = pop.getFactory.createPerson(Id.createPersonId(household.getId + "_P1"))
    pop.addPerson(p)

    val homeCoord = new Coord(0, 0)
    val H11: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H11.setEndTime(8.5 * 3600)
    val W1: Activity = PopulationUtils.createActivityFromCoord("work", new Coord(30, 0))
    W1.setStartTime(9 * 3600)
    W1.setEndTime(17 * 3600)
    val H12: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H12.setStartTime(17.5 * 3600)
    val plan: Plan = pop.getFactory.createPlan()
    plan.setPerson(p)
    plan.addActivity(H11)
    plan.addActivity(W1)
    plan.addActivity(H12)
    p.addPlan(plan)

    household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(p.getId)))
    household.setVehicleIds(JavaConverters.seqAsJavaList(vehicles.map(veh => veh.toStreetVehicle.id)))
    household
  }

  def scenario2(vehicles: List[BeamVehicle])(implicit sc: org.matsim.api.core.v01.Scenario): Household = {
    val pop = sc.getPopulation
    val homeCoord = new Coord(0, 0)
    val household = new HouseholdsFactoryImpl().createHousehold(Id.create("dummyHH", classOf[Household]))

    val P1: Person = pop.getFactory.createPerson(Id.createPersonId(household.getId + "_P1"))
    val H11: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H11.setEndTime(9 * 3600)
    val W1: Activity = PopulationUtils.createActivityFromCoord("work1", new Coord(24166, 13820))
    W1.setEndTime(17 * 3600)
    val H12: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    val plan1: Plan = pop.getFactory.createPlan()
    plan1.setPerson(P1)
    plan1.addActivity(H11)
    plan1.addActivity(W1)
    plan1.addActivity(H12)
    P1.addPlan(plan1)
    pop.addPerson(P1)

    val P2: Person = pop.getFactory.createPerson(Id.createPersonId(household.getId + "_P2"))
    val H21: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H21.setEndTime(9 * 3600 + 5 * 60)
    val W2: Activity = PopulationUtils.createActivityFromCoord("work2", new Coord(20835, 0))
    W2.setEndTime(17 * 3600 + 5 * 60)
    val H22: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    val plan2: Plan = pop.getFactory.createPlan()
    plan2.setPerson(P2)
    plan2.addActivity(H21)
    plan2.addActivity(W2)
    plan2.addActivity(H22)
    P2.addPlan(plan2)
    pop.addPerson(P2)

    household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(P1.getId, P2.getId)))
    household.setVehicleIds(JavaConverters.seqAsJavaList(vehicles.map(veh => veh.toStreetVehicle.id)))
    household
  }

  def scenario3(vehicles: List[BeamVehicle])(implicit sc: org.matsim.api.core.v01.Scenario): Household = {
    val pop = sc.getPopulation
    val homeCoord = new Coord(0, 0)
    val household = new HouseholdsFactoryImpl().createHousehold(Id.create("dummyHH", classOf[Household]))

    val P1: Person = pop.getFactory.createPerson(Id.createPersonId(household.getId + "_P1"))
    val H11: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H11.setEndTime(9 * 3600)
    val W1: Activity = PopulationUtils.createActivityFromCoord("work1", new Coord(24166, 13820))
    W1.setEndTime(17 * 3600)
    val H12: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    val plan1: Plan = pop.getFactory.createPlan()
    plan1.setPerson(P1)
    plan1.addActivity(H11)
    plan1.addActivity(W1)
    plan1.addActivity(H12)
    P1.addPlan(plan1)
    pop.addPerson(P1)

    val P2: Person = pop.getFactory.createPerson(Id.createPersonId(household.getId + "_P2"))
    val H21: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H21.setEndTime(9 * 3600 + 5 * 60)
    val W2: Activity = PopulationUtils.createActivityFromCoord("work2", new Coord(20835, 0))
    W2.setEndTime(18 * 3600)
    val H22: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    val plan2: Plan = pop.getFactory.createPlan()
    plan2.setPerson(P2)
    plan2.addActivity(H21)
    plan2.addActivity(W2)
    plan2.addActivity(H22)
    P2.addPlan(plan2)
    pop.addPerson(P2)

    household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(P1.getId, P2.getId)))
    household.setVehicleIds(JavaConverters.seqAsJavaList(vehicles.map(veh => veh.toStreetVehicle.id)))
    household
  }


  def scenario4(vehicles: List[BeamVehicle])(implicit sc: org.matsim.api.core.v01.Scenario): Household = {
    new PopulationReader(sc).readFile("test/input/dummy/population.xml")
    val p1 = sc.getPopulation.getPersons.get(Id.createPersonId("1"))
    val p2 = sc.getPopulation.getPersons.get(Id.createPersonId("2"))
    val p3 = sc.getPopulation.getPersons.get(Id.createPersonId("3"))

    val household = new HouseholdsFactoryImpl().createHousehold(Id.create("dummy", classOf[Household]))
    household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(p1.getId, p2.getId, p3.getId)))
    household.setVehicleIds(JavaConverters.seqAsJavaList(vehicles.map(veh => veh.toStreetVehicle.id)))
    household
  }

  def getSkim(sc: Scenario, household: Household): Map[BeamMode, Map[Coord, Map[Coord, Double]]] = {
    import beam.agentsim.agents.memberships.Memberships.RankedGroup._
    implicit val pop: org.matsim.api.core.v01.population.Population = sc.getPopulation
    val householdPlans = household.members.map(person => BeamPlan(person.getSelectedPlan)).toList
    val skim = HouseholdCAVScheduling.computeSkim(
      householdPlans,
      Map(BeamMode.CAR -> 50 * 1000 / 3600, BeamMode.TRANSIT -> 40 * 1000 / 3600)
    )
    skim
  }

}
