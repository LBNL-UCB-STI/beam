package beam.agentsim.agents.household
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleManager}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServicesImpl}
import beam.utils.TestConfigUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.population.{Activity, Person, Plan, Population}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting
import org.matsim.core.population.PopulationUtils
import org.matsim.households.{Household, HouseholdsFactoryImpl}
import org.matsim.vehicles.Vehicle
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.funspec.AnyFunSpecLike

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
    with AnyFunSpecLike
    with BeforeAndAfterAll
    with BeamHelper
    with ImplicitSender {

  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = system.dispatcher
  private lazy val beamCfg = BeamConfig(system.settings.config)
  private lazy val beamScenario = loadScenario(beamCfg)
  private val householdsFactory: HouseholdsFactoryImpl = new HouseholdsFactoryImpl()

  private val matsimConfig = new MatSimBeamConfigBuilder(system.settings.config).buildMatSimConf()
  matsimConfig.controler.setOutputDirectory(TestConfigUtils.testOutputDir)
  matsimConfig.controler.setOverwriteFileSetting(OverwriteFileSetting.overwriteExistingFiles)

  private val scenario = buildScenarioFromMatsimConfig(matsimConfig, beamScenario)
  private val injector = buildInjector(system.settings.config, beamCfg, scenario, beamScenario)
  val services = new BeamServicesImpl(injector)

  describe("A Household CAV Scheduler") {
    it("generates two schedules") {
      val cavs = List[BeamVehicle](
        new BeamVehicle(Id.createVehicleId("id1"), new Powertrain(0.0), defaultCAVBeamVehicleType)
      )
      val household = scenario1(cavs)
      val alg = new FastHouseholdCAVScheduling(household, cavs, services)
      alg.waitingTimeInSec = 2
      alg.delayToArrivalInSec = 2
      alg.stopSearchAfterXSolutions = 5000
      alg.limitCavToXPersons = 9999
      val schedules = alg.getAllFeasibleSchedules
      schedules should have length 1
      schedules foreach (_.schedulesMap(cavs.head).schedule should have length 6)
    }

    it("pool two persons for both trips") {
      val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
      val cavs = List[BeamVehicle](
        new BeamVehicle(
          Id.createVehicleId("id1"),
          new Powertrain(0.0),
          defaultCAVBeamVehicleType
        ),
        new BeamVehicle(
          Id.createVehicleId("id2"),
          new Powertrain(0.0),
          vehicleType
        )
      )
      val household = scenario2(cavs)
      val alg = new FastHouseholdCAVScheduling(
        household,
        cavs,
        services
      )
      alg.waitingTimeInSec = 60 * 60
      alg.delayToArrivalInSec = 60 * 60
      alg.stopSearchAfterXSolutions = 5000
      alg.limitCavToXPersons = 9999
      val schedules = alg.getAllFeasibleSchedules
      schedules should have length 3
      schedules foreach (_.schedulesMap(cavs.head).schedule should (have length 1 or (have length 6 or have length 10)))
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
      val household = scenario5(cavs)
      val alg = new FastHouseholdCAVScheduling(
        household,
        cavs,
        services
      )
      alg.waitingTimeInSec = 60 * 60
      alg.delayToArrivalInSec = 60 * 60
      alg.stopSearchAfterXSolutions = 5000
      alg.limitCavToXPersons = 9999
      // first check
      val schedules1 = alg.getAllFeasibleSchedules
      schedules1 should have length 3
      schedules1 foreach (_.schedulesMap(cavs.head).schedule should (have length 1 or (have length 6 or have length 10)))
      // second check
      val schedules2 = alg.getBestProductiveSchedule
      schedules2.foldLeft(0)(_ + _.schedule.size) shouldBe 10
      // third check
      val schedules3 = alg.getKBestSchedules(1)
      schedules3.head.foldLeft(0)(_ + _.schedule.size) shouldBe 10
    }
  }

  private def defaultCAVBeamVehicleType = beamScenario.vehicleTypes(Id.create("CAV", classOf[BeamVehicleType]))

  private def scenario1(vehicles: List[BeamVehicle]): Household = {
    val population = services.matsimServices.getScenario.getPopulation
    val hoseHoldDummyId = Id.create("dummy1", classOf[Household])
    val household = householdsFactory.createHousehold(hoseHoldDummyId)

    val p: Person = population.getFactory.createPerson(Id.createPersonId(household.getId + "_P1"))
    val homeCoord = new Coord(0, 0)
    val H11: Activity = population.getFactory.createActivityFromCoord("home", homeCoord)
    H11.setEndTime(8 * 3600 + 30 * 60)
    val W1: Activity = population.getFactory.createActivityFromCoord("work", new Coord(30, 0))
    W1.setEndTime(17 * 3600)
    val H12: Activity = population.getFactory.createActivityFromCoord("home", homeCoord)
    val plan: Plan = population.getFactory.createPlan()
    plan.setPerson(p)
    plan.addActivity(H11)
    plan.addActivity(W1)
    plan.addActivity(H12)
    p.addPlan(plan)
    population.addPerson(p)

    household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(p.getId)))
    household.setVehicleIds(
      JavaConverters.seqAsJavaList(vehicles.map(veh => Id.create(veh.toStreetVehicle.id, classOf[Vehicle])))
    )
    household
  }

  private def scenario2(vehicles: List[BeamVehicle]): Household = {
    val population = services.matsimServices.getScenario.getPopulation
    val homeCoord = new Coord(0, 0)
    val hoseHoldDummyId = Id.create("dummy2", classOf[Household])
    val household = householdsFactory.createHousehold(hoseHoldDummyId)

    val P1: Person = population.getFactory.createPerson(Id.createPersonId(household.getId + "_P1"))
    val H11: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H11.setEndTime(9 * 3600)
    val W1: Activity = PopulationUtils.createActivityFromCoord("work1", new Coord(24166, 13820))
    W1.setEndTime(17 * 3600)
    val H12: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    val plan1: Plan = population.getFactory.createPlan()
    plan1.setPerson(P1)
    plan1.addActivity(H11)
    plan1.addActivity(W1)
    plan1.addActivity(H12)
    P1.addPlan(plan1)
    population.addPerson(P1)

    val P2: Person = population.getFactory.createPerson(Id.createPersonId(household.getId + "_P2"))
    val H21: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H21.setEndTime(9 * 3600 + 5 * 60)
    val W2: Activity = PopulationUtils.createActivityFromCoord("work2", new Coord(20835, 0))
    W2.setEndTime(17 * 3600 + 5 * 60)
    val H22: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    val plan2: Plan = population.getFactory.createPlan()
    plan2.setPerson(P2)
    plan2.addActivity(H21)
    plan2.addActivity(W2)
    plan2.addActivity(H22)
    P2.addPlan(plan2)
    population.addPerson(P2)

    household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(P1.getId, P2.getId)))
    household.setVehicleIds(
      JavaConverters.seqAsJavaList(vehicles.map(veh => Id.create(veh.toStreetVehicle.id, classOf[Vehicle])))
    )
    household
  }

  private def scenario5(vehicles: List[BeamVehicle]): Household = {
    val population = services.matsimServices.getScenario.getPopulation
    val homeCoord = new Coord(0, 0)
    val hoseHoldDummyId = Id.create("dummy3", classOf[Household])
    val household = householdsFactory.createHousehold(hoseHoldDummyId)

    val P1: Person = population.getFactory.createPerson(Id.createPersonId(household.getId + "_P1"))
    val H11: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H11.setEndTime(9 * 3600)
    val W1: Activity = PopulationUtils.createActivityFromCoord("work1", new Coord(24166, 13820))
    W1.setEndTime(17 * 3600)
    val H12: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    val plan1: Plan = population.getFactory.createPlan()
    plan1.setPerson(P1)
    plan1.addActivity(H11)
    plan1.addActivity(W1)
    plan1.addActivity(H12)
    P1.addPlan(plan1)
    population.addPerson(P1)

    val P2: Person = population.getFactory.createPerson(Id.createPersonId(household.getId + "_P2"))
    val H21: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H21.setEndTime(9 * 3600 + 5 * 60)
    val W2: Activity = PopulationUtils.createActivityFromCoord("work2", new Coord(20835, 0))
    W2.setEndTime(18 * 3600)
    val H22: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    val plan2: Plan = population.getFactory.createPlan()
    plan2.setPerson(P2)
    plan2.addActivity(H21)
    plan2.addActivity(W2)
    plan2.addActivity(H22)
    P2.addPlan(plan2)
    population.addPerson(P2)

    household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(P1.getId, P2.getId)))
    household.setVehicleIds(
      JavaConverters.seqAsJavaList(vehicles.map(veh => Id.create(veh.toStreetVehicle.id, classOf[Vehicle])))
    )
    household
  }
}
