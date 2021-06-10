package beam.agentsim.agents.choice.mode

import scala.util.Random

import beam.agentsim.agents.choice.mode.ModeChoiceMultinomialLogitSpec.{randomIntList, randomPath}
import beam.agentsim.events.SpaceTime
import beam.router.model.BeamPath
import beam.router.model.RoutingModel.TransitStopsInfo
import beam.router.r5.{BikeLanesAdjustment, BikeLanesData}
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle
import org.scalatest.wordspec.AnyWordSpec

class ModeChoiceMultinomialLogitSpec extends AnyWordSpec {

  "ModeChoiceMultinomialLogit" when {
    "pathScaledForWaiting" must {
      "skip the first element of BeamPath and sum all travelTime when bikeLaneScaleFactor is 1" in {
        val value1 = 11.4D
        val value2 = 12.3D
        val path = randomPath(travelTimes = 10D, value1, value2)
        val bikeLaneLinkIds = randomIntList.toSet
        val data = BikeLanesData(scaleFactorFromConfig = 1D, bikeLanesLinkIds = bikeLaneLinkIds)
        val adjustment = new BikeLanesAdjustment(data)

        val result: Double = ModeChoiceMultinomialLogit.pathScaledForWaiting(path, adjustment)

        val clue = s"Input path: [$path]; bikeLaneLinkIds: [$bikeLaneLinkIds]"
        assert(result === value1 + value2, clue)
      }

      "be zero when path has zero or only one element" in {
        val path = if (Random.nextBoolean()) randomPath() else randomPath(travelTimes = 10D)
        val scaleFactor = Random.nextDouble()
        val bikeLaneLinkIds = randomIntList.toSet
        val data = BikeLanesData(scaleFactorFromConfig = scaleFactor, bikeLanesLinkIds = bikeLaneLinkIds)
        val adjustment = new BikeLanesAdjustment(data)

        val result: Double = ModeChoiceMultinomialLogit.pathScaledForWaiting(path, adjustment)

        val clue = s"Input path: [$path]; scaleFactor: [$scaleFactor]; bikeLaneLinkIds: [$bikeLaneLinkIds]"
        assert(result === 0, clue)
      }

      "pathScaledForWaiting scale disproportionately linkIds that are specified on BikeLaneAdjustment" in {
        val value1 = 11.4D
        val value2 = 12.3D
        val sharedLinkId1 = 2
        val nonSharedLinkId = 3
        val linkIds = Seq(1, sharedLinkId1, nonSharedLinkId)
        val path = randomPath(travelTimes = Seq(10D, value1, value2), linkIds)
        val data = new BikeLanesData(
          scaleFactorFromConfig = 2D,
          bikeLanesLinkIds = Set(sharedLinkId1, nonSharedLinkId + 1)
        )
        val adjustment = new BikeLanesAdjustment(data)

        val result: Double = ModeChoiceMultinomialLogit.pathScaledForWaiting(path, adjustment)

        val clue = s"Input path: [$path]"
        assert(result === value1 * 1 / data.scaleFactorFromConfig + value2, clue)
      }

    }
  }

}

object ModeChoiceMultinomialLogitSpec {

  def randomPath(travelTimes: Double*): BeamPath = {
    val linkIds = List.tabulate(travelTimes.size)(_ => Random.nextInt()).toIndexedSeq
    randomPath(travelTimes, linkIds)
  }

  def randomPath(travelTimes: Seq[Double], linkIds: Seq[Int]): BeamPath = {
    val tailSum = if (travelTimes.isEmpty) 0 else travelTimes.tail.sum
    val startPointTime = randomSpaceTime
    val newTime = (tailSum + startPointTime.time - Random.nextInt(2)).toInt
    BeamPath(
      linkIds = linkIds.toIndexedSeq,
      linkTravelTime = travelTimes.toIndexedSeq,
      transitStops = randomOption(randomTransitStops),
      startPoint = startPointTime,
      endPoint = randomSpaceTime.copy(time = newTime),
      distanceInM = Random.nextDouble()
    )
  }

  private def randomOption[T](value: => T): Option[T] = {
    if (Random.nextBoolean()) {
      Some(value)
    } else {
      None
    }
  }

  private def randomTransitStops: TransitStopsInfo = {
    val fromIdx = Random.nextInt(10)
    TransitStopsInfo(
      agencyId = randomShortString,
      routeId = randomShortString,
      vehicleId = Id.create(randomShortString, classOf[Vehicle]),
      fromIdx = fromIdx,
      toIdx = fromIdx + Random.nextInt(10)
    )
  }

  private def randomShortString: String = {
    val anyStringSizeUpperBound = 10
    Random.alphanumeric.take(Random.nextInt(anyStringSizeUpperBound)).mkString
  }

  private def randomSpaceTime: SpaceTime = {
    SpaceTime(
      x = Random.nextDouble(),
      y = Random.nextDouble(),
      time = Random.nextInt(24)
    )
  }

  private def randomIntList: Seq[Int] = {
    val anySizeUpperBound = 10
    List.tabulate(Random.nextInt(anySizeUpperBound))(_ => Random.nextInt())
  }

}
