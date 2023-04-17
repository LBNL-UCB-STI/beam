package beam.physsim.jdeqsim.cacc.roadcapacityadjustmentfunctions

import java.util.concurrent.ThreadLocalRandom
import beam.sim.BeamConfigChangesObservable

import scala.util.Random
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.network.{Link, Network, Node}
import org.matsim.api.core.v01.Id
import org.matsim.core.config.ConfigUtils
import org.matsim.core.network.NetworkUtils
import org.matsim.core.scenario.ScenarioUtils
import org.scalatest.funspec.AnyFunSpec

import scala.collection.JavaConverters._

class Hao2018CaccRoadCapacityAdjustmentFunctionTest extends AnyFunSpec {
  private val NumberOfSecondsInOneHour = 3600

  private val javaRandom: ThreadLocalRandom = ThreadLocalRandom.current
  private val minRoadCapacity = javaRandom.nextDouble(1, 100)
  private val minSpeedMetersPerSecond = javaRandom.nextDouble(0, 200)
  private val iterationNumber = javaRandom.nextInt(0, 200)

  private val config = ConfigFactory
    .parseMap(
      Map(
        "beam.physsim.jdeqsim.cacc.minRoadCapacity"      -> minRoadCapacity,
        "beam.physsim.jdeqsim.cacc.minSpeedMetersPerSec" -> minSpeedMetersPerSecond
      ).asJava
    )
    .withFallback(
      testConfig("test/input/sf-light/sf-light-1k.conf")
    )
    .resolve()

  private val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSimConf()
  matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)

  private val beamConfig = BeamConfig(config)

  private val capacityFunction = new Hao2018CaccRoadCapacityAdjustmentFunction(
    beamConfig,
    iterationNumber,
    null,
    new BeamConfigChangesObservable(beamConfig)
  )

  private val mNetwork: Network = {
    val config = ConfigUtils.createConfig
    val sce = ScenarioUtils.createScenario(config)
    sce.getNetwork
  }

  private def buildLink(
    capacity: Double,
    freeSpeed: Double
  ): Link = {
    val edgeIndex: Long = javaRandom.nextInt()
    val length: Double = javaRandom.nextInt(0, 200)
    val fromNode: Node = NetworkUtils.createNode(Id.create(Random.alphanumeric.take(20).toString(), classOf[Node]))
    val toNode: Node = NetworkUtils.createNode(Id.create(Random.alphanumeric.take(20).toString(), classOf[Node]))

    val linkId = Id.createLinkId(edgeIndex)
    NetworkUtils.createLink(linkId, fromNode, toNode, mNetwork, length, freeSpeed, capacity, 10d)
  }

  describe("Hao2018CaccRoadCapacityAdjustmentFunction") {
    it("consider CACCCategoryRoad when link.capacity >= caccMinRoad AND link.freespeed >= caccMinSpeed") {
      val link = buildLink(capacity = minRoadCapacity + 1, freeSpeed = minSpeedMetersPerSecond + 1)

      assertResult(true) {
        capacityFunction.isCACCCategoryRoad(link)
      }
    }

    it(
      "calculate capacity for cacc road as a function of fractionOnRoad and linkCapacity divided by seconds in one hour"
    ) {
      val linkCapacity = minRoadCapacity + 1
      val link = buildLink(capacity = linkCapacity, freeSpeed = minSpeedMetersPerSecond + 1)
      val fractionOnRoad = javaRandom.nextDouble()
      val expectedCapacity = capacityFunction.calculateCapacity(fractionOnRoad, linkCapacity) / NumberOfSecondsInOneHour
      val simTime = 0

      assertResult(expectedCapacity) {
        capacityFunction.getCapacityWithCACCPerSecond(link, fractionOnRoad, simTime)
      }

    }

    it("calculate capacity for non cacc road as equal to the road capacity divided by number of seconds in one hour") {
      val linkCapacity = minRoadCapacity - 1
      val nonCacLink = buildLink(capacity = linkCapacity, freeSpeed = minSpeedMetersPerSecond + 1)
      val expectedCapacityWithCaccPersecond = linkCapacity / NumberOfSecondsInOneHour
      val simTime = 0

      val fractionOnRoad = javaRandom.nextDouble()
      val result = capacityFunction.getCapacityWithCACCPerSecond(nonCacLink, fractionOnRoad, simTime)

      assertResult(expectedCapacityWithCaccPersecond) {
        result
      }
    }

  }

}
