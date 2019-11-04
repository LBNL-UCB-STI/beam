package beam.agentsim.agents.household
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.router.BeamSkimmer
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServicesImpl}
import beam.utils.TestConfigUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.population.{Activity, Person, Plan, Population}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting
import org.matsim.core.population.PopulationUtils
import org.matsim.core.population.io.PopulationReader
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.households.{Household, HouseholdImpl, HouseholdsFactoryImpl, HouseholdsReaderV10}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, Matchers}

import scala.collection.immutable.List
import scala.collection.{mutable, JavaConverters}
import scala.concurrent.ExecutionContext

class FastHouseholdCAVSchedulingSpec
    extends TestKit(
      ActorSystem(
        name = "FastHouseholdCAVSchedulingSpec",
        config = ConfigFactory
          .parseString(
            """
        akka.log-dead-letters = 10
        akka.actor.debug.fsm = true
        akka.loglevel = debug
        akka.test.timefactor = 2
        """
          )
          .withFallback(testConfig("test/input/beamville/beam.conf").resolve())
      )
    )
    with Matchers
    with FunSpecLike
    with BeforeAndAfterAll
    with MockitoSugar
    with BeamHelper
    with ImplicitSender {

  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = system.dispatcher
  private lazy val beamCfg = BeamConfig(system.settings.config)
  private lazy val beamScenario = loadScenario(beamCfg)

  private val matsimConfig = new MatSimBeamConfigBuilder(system.settings.config).buildMatSimConf()
  matsimConfig.controler.setOutputDirectory(TestConfigUtils.testOutputDir)
  matsimConfig.controler.setOverwriteFileSetting(OverwriteFileSetting.overwriteExistingFiles)

  private val scenario = buildScenarioFromMatsimConfig(matsimConfig, beamScenario)
  private val injector = buildInjector(system.settings.config, beamCfg, scenario, beamScenario)
  val services = new BeamServicesImpl(injector)

  private lazy val skimmer: BeamSkimmer = new BeamSkimmer(services, beamScenario, new GeoUtilsImpl(beamCfg))

  describe("A Household CAV Scheduler") {
    it("generates two schedules") {
      val cavs = List[BeamVehicle](
        new BeamVehicle(
          Id.createVehicleId("id1"),
          new Powertrain(0.0),
          defaultCAVBeamVehicleType
        )
      )
      val (pop: Population, household) = scenario1(cavs)
      val alg = new FastHouseholdCAVScheduling(household, cavs, skimmer = skimmer)(pop)
      alg.waitingTimeInSec = 2
      alg.delayToArrivalInSec = 2
      alg.stopSearchAfterXSolutions = 5000
      alg.limitCavToXPersons = 9999
      val schedules = alg.getAllFeasibleSchedules
      schedules should have length 1
      schedules foreach (_.schedulesMap(cavs.head).schedule should have length 6)
      println(s"*** scenario 1 *** ${schedules.size} combinations")
    }

    it("pool two persons for both trips") {
      val cavs = List[BeamVehicle](
        new BeamVehicle(
          Id.createVehicleId("id1"),
          new Powertrain(0.0),
          defaultCAVBeamVehicleType
        ),
        new BeamVehicle(
          Id.createVehicleId("id2"),
          new Powertrain(0.0),
          beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
        )
      )
      val (pop: Population, household) = scenario2(cavs)
      val alg = new FastHouseholdCAVScheduling(
        household,
        cavs,
        skimmer = skimmer
      )(pop)
      alg.waitingTimeInSec = 60 * 60
      alg.delayToArrivalInSec = 60 * 60
      alg.stopSearchAfterXSolutions = 5000
      alg.limitCavToXPersons = 9999
      val schedules = alg.getAllFeasibleSchedules
      schedules should have length 3
      schedules foreach (_.schedulesMap(cavs.head).schedule should (have length 1 or (have length 6 or have length 10)))
      println(s"*** scenario 2 *** ${schedules.size} combinations")
    }

    it("pool both agents in different CAVs") {
      val cavs = List[BeamVehicle](
        new BeamVehicle(
          Id.createVehicleId("id1"),
          new Powertrain(0.0),
          defaultCAVBeamVehicleType
        ),
        new BeamVehicle(
          Id.createVehicleId("id2"),
          new Powertrain(0.0),
          defaultCAVBeamVehicleType
        )
      )
      val (pop: Population, household) = scenario5(cavs)
      val alg = new FastHouseholdCAVScheduling(
        household,
        cavs,
        skimmer = skimmer
      )(pop)
      alg.waitingTimeInSec = 60 * 60
      alg.delayToArrivalInSec = 60 * 60
      alg.stopSearchAfterXSolutions = 5000
      alg.limitCavToXPersons = 9999
      // first check
      val schedules1 = alg.getAllFeasibleSchedules
      schedules1 should have length 3
      schedules1 foreach (_.schedulesMap(cavs.head).schedule should (have length 1 or (have length 6 or have length 10)))
      println(s"*** scenario 5 *** ${schedules1.size} combinations")
      // second check
      val schedules2 = alg.getBestProductiveSchedule
      schedules2.foldLeft(0)(_ + _.schedule.size) shouldBe 10
      // third check
      val schedules3 = alg.getKBestSchedules(1)
      schedules3.head.foldLeft(0)(_ + _.schedule.size) shouldBe 10
    }

    it("be scalable") {
      var sum = 0
      var count = 0
      val t0 = System.nanoTime()
      val (pop: Population, households) = scenarioPerformance()
      for ((household, vehicles) <- households) {
        val alg =
          new FastHouseholdCAVScheduling(
            household,
            vehicles,
            skimmer = skimmer
          )(pop)
        alg.waitingTimeInSec = 5 * 60
        alg.delayToArrivalInSec = 10 * 60
        alg.stopSearchAfterXSolutions = 1000
        alg.limitCavToXPersons = 9999
        val schedules = alg.getAllFeasibleSchedules
        sum += schedules.size
        count += 1
        //println(s"household [${household.getId}]: ${schedules.size}")
      }
      val t1 = System.nanoTime()
      val elapsed = ((t1 - t0) / 1E9).toInt
      //elapsed shouldBe <(60)
      println(s"*** scenario 6 *** ${sum / count} avg combinations per household, $elapsed sec elapsed ")
    }
  }

  private def defaultCAVBeamVehicleType = beamScenario.vehicleTypes(Id.create("CAV", classOf[BeamVehicleType]))

  private def scenario1(vehicles: List[BeamVehicle]): (Population, Household) = {
    val sc: org.matsim.api.core.v01.Scenario = ScenarioUtils.createMutableScenario(ConfigUtils.createConfig())
    ScenarioUtils.loadScenario(sc)
    val household = new HouseholdsFactoryImpl().createHousehold(Id.create("dummy_scenario1", classOf[Household]))
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
    (sc.getPopulation, household)
  }

  private def scenario2(vehicles: List[BeamVehicle]): (Population, Household) = {
    val sc: org.matsim.api.core.v01.Scenario = ScenarioUtils.createMutableScenario(ConfigUtils.createConfig())
    ScenarioUtils.loadScenario(sc)
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
    (sc.getPopulation, household)
  }

  private def scenarioPerformance(): (Population, List[(Household, List[BeamVehicle])]) = {
    val sc: org.matsim.api.core.v01.Scenario = ScenarioUtils.createMutableScenario(ConfigUtils.createConfig())
    ScenarioUtils.loadScenario(sc)
    new PopulationReader(sc).readFile("test/input/sf-light/sample/25k/population.xml.gz")
    new HouseholdsReaderV10(sc.getHouseholds).readFile("test/input/sf-light/sample/25k/households.xml")
    val households: mutable.ListBuffer[(Household, List[BeamVehicle])] =
      mutable.ListBuffer.empty[(Household, List[BeamVehicle])]
    import scala.collection.JavaConverters._
    for (hh: Household <- sc.getHouseholds.getHouseholds.asScala.values) {
      //hh.asInstanceOf[HouseholdImpl].setMemberIds(hh.getMemberIds)
      val vehicles = List[BeamVehicle](
        new BeamVehicle(
          Id.createVehicleId(hh.getId.toString + "-veh1"),
          new Powertrain(0.0),
          defaultCAVBeamVehicleType
        ),
        new BeamVehicle(
          Id.createVehicleId(hh.getId.toString + "-veh2"),
          new Powertrain(0.0),
          defaultCAVBeamVehicleType
        )
      )
      hh.asInstanceOf[HouseholdImpl]
        .setVehicleIds(JavaConverters.seqAsJavaList(vehicles.map(veh => veh.toStreetVehicle.id)))
      households.append((hh.asInstanceOf[HouseholdImpl], vehicles))
    }
    (sc.getPopulation, households.toList)
  }

  private def scenario5(vehicles: List[BeamVehicle]): (Population, Household) = {
    val sc: org.matsim.api.core.v01.Scenario = ScenarioUtils.createMutableScenario(ConfigUtils.createConfig())
    ScenarioUtils.loadScenario(sc)
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
    (sc.getPopulation, household)
  }
}
