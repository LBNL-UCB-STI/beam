package beam.physsim.jdeqsim

import beam.physsim.bprsim.{BPRSimConfig, BPRSimulation, BufferEventHandler, PhysSimulationSpec, SimEvent}
import beam.physsim.bprsim.PhysSimulationSpec._
import beam.physsim.conditions.DoubleParking
import beam.physsim.jdeqsim.cacc.sim.JDEQSimulation
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.TestConfigUtils.testConfig
import beam.utils.metrics.TemporalEventCounter
import com.typesafe.config.Config
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.api.core.v01.population.Leg
import org.matsim.core.config.{Config => MatsimConfig}
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup
import org.matsim.core.router.util.TravelTime
import org.matsim.core.scenario.MutableScenario
import org.matsim.core.trafficmonitoring.TravelTimeCalculator
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.jdk.CollectionConverters._

/**
  * @author Dmitry Openkov
  */
class PhysSimLinkStatsSpec extends AnyWordSpecLike with Matchers {

  val config: Config = testConfig("test/input/network-relaxation-scenario/beam.conf").resolve()

  val beamConfig: BeamConfig = BeamConfig(config)

  val configBuilder = new MatSimBeamConfigBuilder(config)
  val matsimConfig: MatsimConfig = configBuilder.buildMatSimConf()

  val network: Network = PhysSimulationSpec.readNetwork("test/test-resources/beam/physsim/beamville-network-output.xml")
  val jdeqConfig = new JDEQSimConfigGroup
  private val phySimFlowCapacity: Double = beamConfig.beam.physsim.flowCapacityFactor * 1.0
  jdeqConfig.setFlowCapacityFactor(phySimFlowCapacity)
  jdeqConfig.setStorageCapacityFactor(beamConfig.beam.physsim.storageCapacityFactor)
  jdeqConfig.setSimulationEndTime(beamConfig.matsim.modules.qsim.endTime)

  val linksToAddDP: Set[Id[Link]] = Set(20, 22, 24).map(Id.createLinkId(_))

  "JDEQ Simulation" when {
    "no double-parking happens" must {
      "produce correct travel times" in {
        val scenario: MutableScenario =
          readScenario(matsimConfig, network, "test/test-resources/beam/physsim/physsim-plans-12k.xml.gz")
        val travelTimes = runJdeqPhysSim(scenario)

        def linkTravelTime(linkNum: Int) =
          travelTimes.getLinkTravelTime(network.getLinks.get(Id.createLinkId(linkNum)), 8 * 3600, null, null)

        linkTravelTime(20) shouldBe 1.105 +- 0.001
        linkTravelTime(21) shouldBe 0.355 +- 0.001
        linkTravelTime(22) shouldBe 1.118 +- 0.001
        linkTravelTime(24) shouldBe 0.71 +- 0.001
        linkTravelTime(30) shouldBe 70.9 +- 0.1
        linkTravelTime(31) shouldBe 70.3 +- 0.1
        linkTravelTime(32) shouldBe 0.355 +- 0.001
      }
    }
    "double-parking happens" must {
      "produce correct travel times" in {
        val scenario: MutableScenario =
          readScenario(matsimConfig, network, "test/test-resources/beam/physsim/physsim-plans-12k.xml.gz")
        addDoubleParkingToSomeLegs(scenario)
        val travelTimes = runJdeqPhysSim(scenario)

        def linkTravelTime(linkNum: Int) =
          travelTimes.getLinkTravelTime(network.getLinks.get(Id.createLinkId(linkNum)), 8 * 3600, null, null)

        linkTravelTime(20) shouldBe 18.63 +- 0.01
        linkTravelTime(21) shouldBe 0.355 +- 0.001
        linkTravelTime(22) shouldBe 12.33 +- 0.01
        linkTravelTime(24) shouldBe 4.79 +- 0.01
        linkTravelTime(30) shouldBe 91.1 +- 0.1
        linkTravelTime(31) shouldBe 70.3 +- 0.1
        linkTravelTime(32) shouldBe 0.355 +- 0.001
      }
    }
  }

  "BPR Simulation" when {
    "double-parking happens" must {
      "produce correct travel time" in {
        val scenario: MutableScenario =
          readScenario(matsimConfig, network, "test/test-resources/beam/physsim/physsim-plans-12k.xml.gz")
        addDoubleParkingToSomeLegs(scenario)
        val travelTimes: TravelTime = runBprPhysSim(scenario)

        def linkTravelTime(linkNum: Int) =
          travelTimes.getLinkTravelTime(network.getLinks.get(Id.createLinkId(linkNum)), 12 * 3600, null, null)

        for (linkNum <- List(20, 21, 22, 24, 30, 31, 32))
          println(
            "" + linkNum + ": " + linkTravelTime(linkNum)
          )
        linkTravelTime(20) shouldBe 111.1 +- 0.1
        linkTravelTime(21) shouldBe 0.355 +- 0.001
        linkTravelTime(22) shouldBe 222.26 +- 0.1
        linkTravelTime(24) shouldBe 211.5 +- 0.1
        linkTravelTime(30) shouldBe 1026.3 +- 1
        linkTravelTime(31) shouldBe 70.32 +- 0.1
        linkTravelTime(32) shouldBe 8.036 +- 0.1
      }
    }
    "no double-parking happens" must {
      "produce correct travel time" in {
        val scenario: MutableScenario =
          readScenario(matsimConfig, network, "test/test-resources/beam/physsim/physsim-plans-12k.xml.gz")
        val travelTimes: TravelTime = runBprPhysSim(scenario)

        def linkTravelTime(linkNum: Int) =
          travelTimes.getLinkTravelTime(network.getLinks.get(Id.createLinkId(linkNum)), 12 * 3600, null, null)

        for (linkNum <- List(20, 21, 22, 24, 30, 31, 32))
          println(
            "" + linkNum + ": " + linkTravelTime(linkNum)
          )
        linkTravelTime(20) shouldBe 1.602 +- 0.001
        linkTravelTime(21) shouldBe 0.355 +- 0.001
        linkTravelTime(22) shouldBe 0.744 +- 0.001
        linkTravelTime(24) shouldBe 1.067 +- 0.001
        linkTravelTime(30) shouldBe 1021.8 +- 1
        linkTravelTime(31) shouldBe 70.32 +- 0.1
        linkTravelTime(32) shouldBe 8.03 +- 0.01
      }
    }
  }

  private def addDoubleParkingToSomeLegs(scenario: MutableScenario): Unit = {
    val persons = scenario.getPopulation.getPersons.asScala
    persons.values.foreach { p =>
      val legs = p.getSelectedPlan.getPlanElements.asScala.collect {
        case leg: Leg if linksToAddDP.contains(leg.getRoute.getEndLinkId) => leg
      }
      legs.foreach(_.getAttributes.putAttribute("ended_with_double_parking", true))
    }
  }

  private def runJdeqPhysSim(scenario: MutableScenario): TravelTime = {
    val travelTimeCalculatorBuilder = new TravelTimeCalculator.Builder(network)
    travelTimeCalculatorBuilder.configure(matsimConfig.travelTimeCalculator)
    val travelTimeCalculator = travelTimeCalculatorBuilder.build()
    val eventsManager = new EventsManagerImpl
    eventsManager.addHandler(travelTimeCalculator)
    val sim = new JDEQSimulation(
      jdeqConfig,
      beamConfig,
      scenario,
      eventsManager,
      new DoubleParking.SimpleCapacityReductionFunction(),
      new TemporalEventCounter[Id[Link]](30),
      None,
      None
    )
    sim.run()
    travelTimeCalculator.getLinkTravelTimes
  }

  private def runBprPhysSim(scenario: MutableScenario) = {
    val bprConfig = BPRSimConfig(
      jdeqConfig.getSimulationEndTime.seconds(),
      1,
      0,
      phySimFlowCapacity,
      beamConfig.beam.physsim.bprsim.inFlowAggregationTimeWindowInSeconds,
      JDEQSimRunner.getTravelTimeFunction(
        "BPR",
        phySimFlowCapacity,
        1,
        new DoubleParking.SimpleCapacityReductionFunction(),
        None,
        None,
        defaultAlpha = beamConfig.beam.physsim.network.overwriteRoadTypeProperties.default.alpha,
        defaultBeta = beamConfig.beam.physsim.network.overwriteRoadTypeProperties.default.beta,
        minSpeed = beamConfig.beam.physsim.minCarSpeedInMetersPerSecond * 0.1
      ),
      None
    )
    val travelTimeCalculatorBuilder = new TravelTimeCalculator.Builder(network)
    travelTimeCalculatorBuilder.configure(matsimConfig.travelTimeCalculator)
    val travelTimeCalculator = travelTimeCalculatorBuilder.build()
    val eventsManager = new EventsManagerImpl
    eventsManager.addHandler(travelTimeCalculator)
    eventsManager.initProcessing()
    val sim = new BPRSimulation(scenario, bprConfig, eventsManager)
    sim.run()
    eventsManager.finishProcessing()
    val travelTimes = travelTimeCalculator.getLinkTravelTimes
    travelTimes
  }
}
