package beam.agentsim.agents.household
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import beam.agentsim.agents.choice.mode.ModeIncentive.Incentive
import beam.agentsim.agents.choice.mode.{ModeIncentive, PtFares}
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.ModeChoiceCalculatorFactory
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.FuelType.{FuelType, Gasoline}
import beam.agentsim.agents.vehicles.VehicleCategory.Car
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleEnergy}
import beam.agentsim.agents.{Dropoff, Pickup}
import beam.agentsim.infrastructure.TAZTreeMap
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{BeamVehicleUtils, DateUtils, MatsimServicesMock, NetworkHelper}
import com.google.inject.Injector
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.population.{Activity, Person, Plan, Population}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.controler.{ControlerI, MatsimServices}
import org.matsim.core.population.PopulationUtils
import org.matsim.core.population.io.PopulationReader
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.households.{Household, HouseholdImpl, HouseholdsFactoryImpl, HouseholdsReaderV10}
import org.matsim.vehicles.Vehicle
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, Matchers}

import scala.collection.concurrent.TrieMap
import scala.collection.immutable.List
import scala.collection.{mutable, JavaConverters}
import scala.concurrent.ExecutionContext

class FastHouseholdCAVSchedulingSpec
    extends TestKit(
      ActorSystem(
        name = "FastHouseholdCAVSchedulingTest",
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
    with ImplicitSender {

  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = system.dispatcher
  private lazy val beamCfg = BeamConfig(system.settings.config)
  private val householdsFactory: HouseholdsFactoryImpl = new HouseholdsFactoryImpl()
  private val configBuilder = new MatSimBeamConfigBuilder(system.settings.config)
  private val matsimConfig = configBuilder.buildMatSimConf()

  private lazy val beamSvc: BeamServices = new BeamServices {
    override lazy val injector: Injector = ???
    val tazTreeMap: TAZTreeMap = BeamServices.getTazTreeMap("test/input/beamville/taz-centers.csv")
    val beamConfig = beamCfg

    override var matsimServices: MatsimServices = new MatsimServicesMock(null, mock[Scenario])

    override val modeIncentives = ModeIncentive(Map[BeamMode, List[Incentive]]())

    val fuelTypePrices: Map[FuelType, Double] =
      BeamVehicleUtils.readFuelTypeFile(beamConfig.beam.agentsim.agents.vehicles.fuelTypesFilePath).toMap

    val vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType] =
      BeamVehicleUtils.readBeamVehicleTypeFile(
        beamConfig.beam.agentsim.agents.vehicles.vehicleTypesFilePath,
        fuelTypePrices
      )

    override val geo: GeoUtils = new GeoUtilsImpl(beamConfig)
    override lazy val controler: ControlerI = ???
    override lazy val vehicleEnergy: VehicleEnergy = ???
    override var modeChoiceCalculatorFactory: ModeChoiceCalculatorFactory = _
    override lazy val dates: DateUtils = ???
    override var beamRouter: ActorRef = _
    override lazy val rideHailTransitModes: Seq[BeamMode] = ???
    override lazy val agencyAndRouteByVehicleIds: TrieMap[Id[Vehicle], (String, String)] = ???
    override var personHouseholds: Map[Id[Person], Household] = _
    override lazy val privateVehicles: TrieMap[Id[BeamVehicle], BeamVehicle] = ???
    override lazy val ptFares: PtFares = ???
    override def startNewIteration(): Unit = ???
    override def networkHelper: NetworkHelper = ???
    override def setTransitFleetSizes(tripFleetSizeMap: mutable.HashMap[String, Integer]): Unit = ???
  }

  private lazy val skimmer: BeamSkimmer = new BeamSkimmer(beamCfg, beamSvc)

  describe("A Household CAV Scheduler") {
    it("generates two schedules") {
      val cavs = List[BeamVehicle](
        new BeamVehicle(
          Id.createVehicleId("id1"),
          new Powertrain(0.0),
          FastHouseholdCAVSchedulingSpec.defaultCAVBeamVehicleType
        )
      )
      val (pop: Population, household) = FastHouseholdCAVSchedulingSpec.scenario1(cavs)
      val alg = new FastHouseholdCAVScheduling(household, cavs, Map((Pickup, 2), (Dropoff, 2)), skimmer = skimmer)(pop)
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
          FastHouseholdCAVSchedulingSpec.defaultCAVBeamVehicleType
        ),
        new BeamVehicle(
          Id.createVehicleId("id2"),
          new Powertrain(0.0),
          BeamVehicleType.defaultCarBeamVehicleType
        )
      )
      val (pop: Population, household) = FastHouseholdCAVSchedulingSpec.scenario2(cavs)
      val alg = new FastHouseholdCAVScheduling(
        household,
        cavs,
        Map((Pickup, 60 * 60), (Dropoff, 60 * 60)),
        stopSearchAfterXSolutions = 5000,
        skimmer = skimmer
      )(pop)
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
          FastHouseholdCAVSchedulingSpec.defaultCAVBeamVehicleType
        ),
        new BeamVehicle(
          Id.createVehicleId("id2"),
          new Powertrain(0.0),
          FastHouseholdCAVSchedulingSpec.defaultCAVBeamVehicleType
        )
      )
      val (pop: Population, household) = FastHouseholdCAVSchedulingSpec.scenario5(cavs)
      val alg = new FastHouseholdCAVScheduling(
        household,
        cavs,
        Map((Pickup, 60 * 60), (Dropoff, 60 * 60)),
        stopSearchAfterXSolutions = 5000,
        skimmer = skimmer
      )(pop)
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
      val (pop: Population, households) = FastHouseholdCAVSchedulingSpec.scenarioPerformance()
      for ((household, vehicles) <- households) {
        val alg =
          new FastHouseholdCAVScheduling(
            household,
            vehicles,
            Map((Pickup, 5 * 60), (Dropoff, 10 * 60)),
            stopSearchAfterXSolutions = 1000,
            limitCavToXPersons = Int.MaxValue,
            skimmer = skimmer
          )(pop)
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
}

object FastHouseholdCAVSchedulingSpec {

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

  def scenario1(vehicles: List[BeamVehicle]): (Population, Household) = {
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

  def scenario2(vehicles: List[BeamVehicle]): (Population, Household) = {
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

  def scenarioPerformance(): (Population, List[(Household, List[BeamVehicle])]) = {
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
          FastHouseholdCAVSchedulingSpec.defaultCAVBeamVehicleType
        ),
        new BeamVehicle(
          Id.createVehicleId(hh.getId.toString + "-veh2"),
          new Powertrain(0.0),
          FastHouseholdCAVSchedulingSpec.defaultCAVBeamVehicleType
        )
      )
      hh.asInstanceOf[HouseholdImpl]
        .setVehicleIds(JavaConverters.seqAsJavaList(vehicles.map(veh => veh.toStreetVehicle.id)))
      households.append((hh.asInstanceOf[HouseholdImpl], vehicles))
    }
    (sc.getPopulation, households.toList)
  }

  def scenario5(vehicles: List[BeamVehicle]): (Population, Household) = {
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
