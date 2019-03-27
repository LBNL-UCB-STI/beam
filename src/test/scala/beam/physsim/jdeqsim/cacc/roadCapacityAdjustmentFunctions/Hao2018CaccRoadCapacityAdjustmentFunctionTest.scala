package beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions
import java.util.concurrent.ThreadLocalRandom

import scala.util.Random

import org.matsim.api.core.v01.network.{Link, Network, Node}
import org.matsim.api.core.v01.Id
import org.matsim.core.config.ConfigUtils
import org.matsim.core.network.NetworkUtils
import org.matsim.core.scenario.ScenarioUtils
import org.scalatest.FunSpec

class Hao2018CaccRoadCapacityAdjustmentFunctionTest extends FunSpec {
  private val NumberOfSecondsInOneHour = 3600

  private val javaRandom: ThreadLocalRandom = ThreadLocalRandom.current
  private val minRoadCapacity = javaRandom.nextDouble(1, 100)
  private val minSpeedMetersPerSecond = javaRandom.nextDouble(0, 200)
  private val flowCapacityFactor = javaRandom.nextDouble(0, 200)
  private val iterationNumber = javaRandom.nextInt(0, 200)
  private val writeInterval = javaRandom.nextInt(0, 20)

  private val capacityFunction = new Hao2018CaccRoadCapacityAdjustmentFunction(
    minRoadCapacity,
    minSpeedMetersPerSecond,
    flowCapacityFactor,
    iterationNumber,
    null,
    writeInterval
  )

  val mNetwork: Network = {
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
    NetworkUtils.createLink(linkId, fromNode, toNode, mNetwork, length, freeSpeed, capacity, 10D)
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
      val fractionOnRoad = 10D
      val expectedCapacity = capacityFunction.calculateCapacity(fractionOnRoad, linkCapacity) / NumberOfSecondsInOneHour

      assertResult(expectedCapacity) {
        capacityFunction.getCapacityWithCACCPerSecond(link, fractionOnRoad)
      }

    }

    it("calculate capacity for non cacc road as equal to the road capacity divided by number of seconds in one hour") {
      val linkCapacity = minRoadCapacity - 1
      val nonCacLink = buildLink(capacity = linkCapacity, freeSpeed = minSpeedMetersPerSecond + 1)
      val expectedCapacityWithCaccPersecond = linkCapacity / NumberOfSecondsInOneHour

      val fractionOnRoad = 10D
      val result = capacityFunction.getCapacityWithCACCPerSecond(nonCacLink, fractionOnRoad)

      assertResult(expectedCapacityWithCaccPersecond) {
        result
      }
    }

  }

}
