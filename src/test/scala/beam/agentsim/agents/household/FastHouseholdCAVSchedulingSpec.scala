package beam.agentsim.agents.household
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import beam.agentsim.agents.choice.mode.ModeIncentive
import beam.agentsim.agents.choice.mode.ModeIncentive.Incentive
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.agents.{Dropoff, Pickup}
import beam.agentsim.infrastructure.TAZTreeMap
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.DefaultVehicleTypeUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.population.Population
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.controler.MatsimServices
import org.matsim.households.HouseholdsFactoryImpl
import org.mockito.Mockito.{when, withSettings}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, Matchers}

import scala.collection.immutable.List
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
  private lazy val beamConfig = BeamConfig(system.settings.config)
  private val householdsFactory: HouseholdsFactoryImpl = new HouseholdsFactoryImpl()
  private val configBuilder = new MatSimBeamConfigBuilder(system.settings.config)
  private val matsimConfig = configBuilder.buildMatSamConf()
  private val skimmer: BeamSkimmer = new BeamSkimmer()

  private lazy val beamSvc: BeamServices = {
    val tAZTreeMap: TAZTreeMap = BeamServices.getTazTreeMap("test/input/beamville/taz-centers.csv")
    val theServices = mock[BeamServices](withSettings().stubOnly())
    when(theServices.matsimServices).thenReturn(mock[MatsimServices])
    when(theServices.matsimServices.getScenario).thenReturn(mock[Scenario])
    when(theServices.matsimServices.getScenario.getNetwork).thenReturn(mock[Network])
    when(theServices.beamConfig).thenReturn(beamConfig)
    when(theServices.tazTreeMap).thenReturn(tAZTreeMap)
    when(theServices.geo).thenReturn(new GeoUtilsImpl(beamConfig))
    when(theServices.modeIncentives).thenReturn(ModeIncentive(Map[BeamMode, List[Incentive]]()))
    when(theServices.fuelTypePrices).thenReturn(mock[Map[FuelType, Double]])
    when(theServices.vehicleTypes).thenReturn(Map[Id[BeamVehicleType], BeamVehicleType]())
    theServices
  }

  describe("A Household CAV Scheduler") {
    it("generates two schedules") {
      val cavs = List[BeamVehicle](
        new BeamVehicle(
          Id.createVehicleId("id1"),
          new Powertrain(0.0),
          HouseholdCAVSchedulingTest.defaultCAVBeamVehicleType
        )
      )
      val (pop: Population, household) = HouseholdCAVSchedulingTest.scenario1(cavs)

      val alg = new FastHouseholdCAVScheduling(household, cavs, Map((Pickup, 2), (Dropoff, 2)), skimmer = skimmer)(pop)
      val schedules = alg.getAllFeasibleSchedules
      schedules should have length 1
      schedules foreach (_.schedulesMap(cavs.head) should (have length 1 or have length 6))
      println(s"*** scenario 1 *** ${schedules.size} combinations")
    }

    it("pool two persons for both trips") {
      val cavs = List[BeamVehicle](
        new BeamVehicle(
          Id.createVehicleId("id1"),
          new Powertrain(0.0),
          HouseholdCAVSchedulingTest.defaultCAVBeamVehicleType
        ),
        new BeamVehicle(
          Id.createVehicleId("id2"),
          new Powertrain(0.0),
          DefaultVehicleTypeUtils.defaultCarBeamVehicleType
        )
      )
      val (pop: Population, household) = HouseholdCAVSchedulingTest.scenario2(cavs)
      val alg = new FastHouseholdCAVScheduling(
        household,
        cavs,
        Map((Pickup, 60 * 60), (Dropoff, 60 * 60)),
        stopSearchAfterXSolutions = 5000,
        skimmer = skimmer
      )(pop)
      val schedules = alg.getAllFeasibleSchedules
      schedules should have length 3
      schedules foreach (_.schedulesMap(cavs.head) should (have length 1 or (have length 6 or have length 10)))
      println(s"*** scenario 2 *** ${schedules.size} combinations")
    }

    it("pool both agents in different CAVs") {
      val cavs = List[BeamVehicle](
        new BeamVehicle(
          Id.createVehicleId("id1"),
          new Powertrain(0.0),
          HouseholdCAVSchedulingTest.defaultCAVBeamVehicleType
        ),
        new BeamVehicle(
          Id.createVehicleId("id2"),
          new Powertrain(0.0),
          HouseholdCAVSchedulingTest.defaultCAVBeamVehicleType
        )
      )
      val (pop: Population, household) = HouseholdCAVSchedulingTest.scenario5(cavs)
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
      schedules1 foreach (_.schedulesMap(cavs.head) should (have length 1 or (have length 6 or have length 10)))
      println(s"*** scenario 5 *** ${schedules1.size} combinations")
      // second check
      val schedules2 = alg.getBestScheduleWithLongestChain
      println(schedules2)
      // third check
      val schedules3 = alg.getKBestSchedules(1)
      println(schedules3)
    }

    it("be scalable") {
      var sum = 0
      var count = 0
      val t0 = System.nanoTime()
      val (pop: Population, households) = HouseholdCAVSchedulingTest.scenarioPerformance()
      for ((household, vehicles) <- households) {
        val alg =
          new FastHouseholdCAVScheduling(
            household,
            vehicles,
            Map((Pickup, 15 * 60), (Dropoff, 15 * 60)),
            stopSearchAfterXSolutions = Int.MaxValue,
            skimmer = skimmer
          )(pop)
        val schedules = alg.getAllFeasibleSchedules
        sum += schedules.size
        count += 1
        //println(s"household [${household.getId}]: ${schedules.size}")
      }
      val t1 = System.nanoTime()
      val elapsed = ((t1 - t0) / 1E9).toInt
      elapsed shouldBe <(60)
      println(s"*** scenario 6 *** ${sum / count} avg combinations per household, $elapsed sec elapsed ")
    }
  }

}
