package beam.agentsim.agents.household
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import beam.agentsim.agents.choice.mode.ModeIncentive
import beam.agentsim.agents.choice.mode.ModeIncentive.Incentive
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.FuelType.{FuelType, Gasoline}
import beam.agentsim.agents.vehicles.VehicleCategory.Car
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.infrastructure.TAZTreeMap
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.population.{Activity, Person, Plan}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.controler.MatsimServices
import org.matsim.core.population.PopulationUtils
import org.matsim.core.population.io.PopulationReader
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.households.{Household, HouseholdsFactoryImpl, HouseholdsReaderV10}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, Matchers}

import scala.collection.immutable.List
import scala.collection.{mutable, JavaConverters}
import scala.concurrent.ExecutionContext

class HouseholdCAVSchedulingTest
    extends TestKit(
      ActorSystem(
        name = "HouseholdCAVSchedulingTestSpec",
        config = ConfigFactory
          .parseString(
            """
        akka.log-dead-letters = 10
        akka.actor.debug.fsm = true
        akka.loglevel = debug
        """
          )
          .withFallback(testConfig("test/input/beamville/beam.conf").resolve())
      )
    )
    with Matchers
    with FunSpecLike
    with BeforeAndAfterAll
    with MockitoSugar
    with ImplicitSender {

  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = system.dispatcher
  private lazy val beamConfig = BeamConfig(system.settings.config)
  private val householdsFactory: HouseholdsFactoryImpl = new HouseholdsFactoryImpl()
  private val configBuilder = new MatSimBeamConfigBuilder(system.settings.config)
  private val matsimConfig = configBuilder.buildMatSamConf()

  private lazy val beamSvc: BeamServices = {
    val scenario = ScenarioUtils.createMutableScenario(matsimConfig)
    ScenarioUtils.loadScenario(ScenarioUtils.createMutableScenario(matsimConfig))
    val tAZTreeMap: TAZTreeMap = BeamServices.getTazTreeMap("test/input/beamville/taz-centers.csv")
    val theServices = mock[BeamServices](withSettings().stubOnly())
    when(theServices.matsimServices).thenReturn(mock[MatsimServices])
    when(theServices.matsimServices.getScenario).thenReturn(mock[Scenario], scenario)
    when(theServices.matsimServices.getScenario.getNetwork).thenReturn(mock[Network])
    when(theServices.beamConfig).thenReturn(beamConfig)
    when(theServices.tazTreeMap).thenReturn(tAZTreeMap)
    when(theServices.geo).thenReturn(new GeoUtilsImpl(beamConfig))
    when(theServices.modeIncentives).thenReturn(ModeIncentive(Map[BeamMode, List[Incentive]]()))
    when(theServices.fuelTypePrices).thenReturn(mock[Map[FuelType, Double]])
    when(theServices.vehicleTypes).thenReturn(Map[Id[BeamVehicleType], BeamVehicleType]())
    theServices
  }

  describe("HouseholdCAVScheduling") {

    val defaultCAVBeamVehicleType = BeamVehicleType(
      Id.create("CAV-TYPE-DEFAULT", classOf[BeamVehicleType]),
      4,
      0,
      4.5,
      Gasoline,
      3656.0,
      3655980000.0,
      vehicleCategory = Car,
      automationLevel = 5
    )

    it("generate two schedules") {
      val cavs = List[BeamVehicle](
        new BeamVehicle(Id.createVehicleId("id1"), new Powertrain(0.0), defaultCAVBeamVehicleType)
      )
      val household: Household = scenario1(cavs)(beamSvc.matsimServices.getScenario)
      val skim = new BeamSkimmer()

      val alg = new HouseholdCAVScheduling(
        household,
        cavs,
        Map((Pickup, 2), (Dropoff, 2)),
        skim = skim,
        beamServices = beamSvc
      )
      val schedules = alg.getAllFeasibleSchedules()
      schedules should have length 2
      schedules.foreach { x =>
        x.cavFleetSchedule should have length 1
        x.cavFleetSchedule.head.schedule should (have length 1 or have length 6)
      }
      println(s"*** scenario 1 *** ${schedules.size} combinations")
      println(schedules)
    }

    it("pool two persons for both trips") {
      val vehicles = List[BeamVehicle](
        new BeamVehicle(Id.createVehicleId("id1"), new Powertrain(0.0), defaultCAVBeamVehicleType),
        new BeamVehicle(Id.createVehicleId("id2"), new Powertrain(0.0), BeamVehicleType.defaultCarBeamVehicleType)
      )
      val household: Household = scenario2(vehicles)(beamSvc.matsimServices.getScenario)
      val skim = new BeamSkimmer()

      val alg = new HouseholdCAVScheduling(
        household,
        vehicles,
        Map((Pickup, 60 * 60), (Dropoff, 60 * 60)),
        skim = skim,
        stopSearchAfterXSolutions = 5000,
        beamServices = beamSvc
      )
      val schedule = alg.getBestScheduleWithTheLongestCAVChain.head

      schedule.cavFleetSchedule should have length 1
      schedule.cavFleetSchedule.head.schedule should have length 10
      schedule.cavFleetSchedule.head.schedule
        .filter(_.person.isDefined)
        .groupBy(_.person)
        .mapValues(_.size)
        .foldLeft(0)(_ + _._2) shouldBe 8
      schedule.cavFleetSchedule.head.schedule(0).tag shouldBe Dropoff
      schedule.cavFleetSchedule.head.schedule(1).tag shouldBe Dropoff
      schedule.cavFleetSchedule.head.schedule(7).tag shouldBe Pickup
      schedule.cavFleetSchedule.head.schedule(8).tag shouldBe Pickup

      println(s"*** scenario 2 *** ")
      println(schedule)
    }

    it("generate twelve trips") {
      val vehicles = List[BeamVehicle](
        new BeamVehicle(Id.createVehicleId("id1"), new Powertrain(0.0), defaultCAVBeamVehicleType)
      )
      val household: Household = scenario4(vehicles)(beamSvc.matsimServices.getScenario)

      val skim = new BeamSkimmer()

      val alg = new HouseholdCAVScheduling(
        household,
        vehicles,
        Map((Pickup, 60 * 60), (Dropoff, 60 * 60)),
        stopSearchAfterXSolutions = 2000,
        skim = skim,
        beamServices = beamSvc
      )
      val schedule = alg.getBestScheduleWithTheLongestCAVChain.head

      schedule.cavFleetSchedule should have length 1
      val nbOfTrips = schedule.cavFleetSchedule.flatMap(_.schedule).count(x => x.tag == Pickup || x.tag == Dropoff) / 2
      nbOfTrips should equal(12)
      println(s"*** scenario 4 *** $nbOfTrips trips")
      println(schedule)
    }

    it("pool both agents in different CAVs") {
      val vehicles = List[BeamVehicle](
        new BeamVehicle(Id.createVehicleId("id1"), new Powertrain(0.0), defaultCAVBeamVehicleType),
        new BeamVehicle(Id.createVehicleId("id2"), new Powertrain(0.0), defaultCAVBeamVehicleType)
      )
      val household: Household = scenario5(vehicles)(beamSvc.matsimServices.getScenario)
      val skim = new BeamSkimmer()

      val alg = new HouseholdCAVScheduling(
        household,
        vehicles,
        Map((Pickup, 60 * 60), (Dropoff, 60 * 60)),
        skim = skim,
        stopSearchAfterXSolutions = 5000,
        beamServices = beamSvc
      )
      val schedule = alg.getKBestSchedules(Integer.MAX_VALUE)
      schedule should have length 25
      val worstCombination = schedule.last
      worstCombination.cavFleetSchedule should have length 2
      worstCombination.cavFleetSchedule.head.schedule should have length 6
      worstCombination.cavFleetSchedule.head.schedule(0).tag shouldBe Dropoff
      worstCombination.cavFleetSchedule.head.schedule(1).tag shouldBe Dropoff
      worstCombination.cavFleetSchedule.last.schedule should have length 5
      worstCombination.cavFleetSchedule.last.schedule(0).tag shouldBe Dropoff
      worstCombination.cavFleetSchedule.last.schedule(1).tag shouldBe Dropoff
      println(s"*** scenario 5 ***")
      println(worstCombination)
    }

    it("be scalable") {
      val vehicles = List[BeamVehicle](
        new BeamVehicle(Id.createVehicleId("id1"), new Powertrain(0.0), defaultCAVBeamVehicleType),
        new BeamVehicle(Id.createVehicleId("id2"), new Powertrain(0.0), defaultCAVBeamVehicleType)
      )
      val household: Household = scenarioPerformance(vehicles)(beamSvc.matsimServices.getScenario)
      val skim = new BeamSkimmer()

      val alg =
        new HouseholdCAVScheduling(
          household,
          vehicles,
          Map((Pickup, 15 * 60), (Dropoff, 15 * 60)),
          skim = skim,
          beamServices = beamSvc
        )
      val schedule = alg.getAllFeasibleSchedules()

      println(s"*** scenario 6 ***")
      println(schedule.size)
    }

  }

  // ******************
  // Scenarios
  // ******************
  def scenario1(vehicles: List[BeamVehicle])(implicit sc: org.matsim.api.core.v01.Scenario): Household = {
    val household = householdsFactory.createHousehold(Id.create("dummy_scenario1", classOf[Household]))
    val popFactory = sc.getPopulation.getFactory

    val p: Person = popFactory.createPerson(Id.createPersonId(household.getId + "_P1"))
    val homeCoord = new Coord(0, 0)
    val H11: Activity = popFactory.createActivityFromCoord("home", homeCoord)
    H11.setEndTime(8 * 3600 + 30 * 60)
    val W1: Activity = popFactory.createActivityFromCoord("work", new Coord(30, 0))
    W1.setEndTime(17 * 3600)
    val H12: Activity = popFactory.createActivityFromCoord("home", homeCoord)
    val plan: Plan = popFactory.createPlan()
    plan.setPerson(p)
    plan.addActivity(H11)
    plan.addActivity(W1)
    plan.addActivity(H12)
    p.addPlan(plan)
    sc.getPopulation.addPerson(p)

    household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(p.getId)))
    household.setVehicleIds(JavaConverters.seqAsJavaList(vehicles.map(veh => veh.toStreetVehicle.id)))
    household
  }

  def scenario2(vehicles: List[BeamVehicle])(implicit sc: org.matsim.api.core.v01.Scenario): Household = {
    val pop = sc.getPopulation
    val homeCoord = new Coord(0, 0)
    val household = new HouseholdsFactoryImpl().createHousehold(Id.create("dummyHH_scenario2", classOf[Household]))

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

  def scenario5(vehicles: List[BeamVehicle])(implicit sc: org.matsim.api.core.v01.Scenario): Household = {
    val pop = sc.getPopulation
    val homeCoord = new Coord(0, 0)
    val household = new HouseholdsFactoryImpl().createHousehold(Id.create("dummyHH_scenario5", classOf[Household]))

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
    new PopulationReader(sc).readFile("test/input/beamville/population.xml")
    val p1 = sc.getPopulation.getPersons.get(Id.createPersonId("1"))
    val p2 = sc.getPopulation.getPersons.get(Id.createPersonId("2"))
    val p3 = sc.getPopulation.getPersons.get(Id.createPersonId("3"))

    val household = new HouseholdsFactoryImpl().createHousehold(Id.create("dummy_scenario4", classOf[Household]))
    household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(p1.getId, p2.getId, p3.getId)))
    household.setVehicleIds(JavaConverters.seqAsJavaList(vehicles.map(veh => veh.toStreetVehicle.id)))
    household
  }

  def scenarioPerformance(vehicles: List[BeamVehicle])(implicit sc: org.matsim.api.core.v01.Scenario): Household = {

    new PopulationReader(sc).readFile("test/input/sf-light/sample/1k/population.xml")
    new HouseholdsReaderV10(sc.getHouseholds).readFile("test/input/sf-light/sample/1k/households.xml")

    val household =
      new HouseholdsFactoryImpl().createHousehold(Id.create("dummy_scenarioPerformance", classOf[Household]))
    household.setMemberIds(
      sc.getHouseholds.getHouseholds.get(Id.create("031400-2014000788156-0", classOf[Household])).getMemberIds
    )
    household.setVehicleIds(JavaConverters.seqAsJavaList(vehicles.map(veh => veh.toStreetVehicle.id)))
    household
  }

}
