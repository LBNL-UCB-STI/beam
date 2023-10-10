package beam.agentsim.agents.ridehail

import beam.sim.BeamHelper
import beam.utils.EventReader._
import beam.utils.MathUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.events.Event
import org.scalatest.AppendedClues.convertToClueful
import org.scalatest.Inspectors.forAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
  * @author Dmitry Openkov
  */
class RideHailStopsSpec extends AnyWordSpecLike with Matchers with BeamHelper {

  "RideHail stops feature" should {
    "RH vehicles move passengers only between the RH stops" in {
      // We define 2 stops only in file ridehail-stops.csv
      // All RH moves with passengers must be between these 2 locations
      val stop1 = LooseCoord(0.04, 0.04, 2)
      val stop2 = LooseCoord(0.02, 0.0, 2)
      // we set ride_hail_transit_intercept = -100 so that people don't use RH before / after transit: in this case
      // we should have people use RH directly between activities and we can test WALK legs easier
      val config = BeamHelper.updateConfigToCurrentVersion(
        ConfigFactory
          .parseString("""
              beam.cfg.copyRideHailToFirstManager = true
              beam.agentsim.agents.rideHail.stopFilePath="./test/test-resources/beam/input/ridehail-stops.csv"
              beam.agentsim.lastIteration = 0
              beam.agentsim.agents.rideHail.maximumWalkDistanceToStopInM=1600
              beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_intercept = 10
              beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_transit_intercept = -100
              beam.physsim.skipPhysSim = true
              beam.debug.stuckAgentDetection.enabled = false
              beam.debug.stuckAgentDetection.checkMaxNumberOfMessagesEnabled = false
              beam.outputs.events.fileOutputFormats = "csv.gz"
          """)
          .withFallback(testConfig("test/input/beamville/beam.conf"))
          .resolve()
      )

      val (matSimConfig, _, _) = runBeamWithConfig(config)

      val filePath = getEventsFilePath(matSimConfig, "events", "csv.gz").getAbsolutePath
      val neededTypes = Set("PathTraversal", "PersonEntersVehicle", "PersonLeavesVehicle", "actstart", "actend")
      val (eventIterator, closable) = fromCsvFile(filePath, event => neededTypes.contains(event.getEventType))
      val events = eventIterator.toList
      closable.close()

      def getEventBefore(time: Int, ofType: String, attrName: String, attrValue: String): Option[Event] = {
        events.view
          .takeWhile(event => getIntAttr(event, "time") <= time)
          .filter(event => event.getAttributes.get("type") == ofType && event.getAttributes.get(attrName) == attrValue)
          .lastOption
      }

      def getEventAfter(time: Int, ofType: String, attrName: String, attrValue: String): Option[Event] = {
        events
          .find(event =>
            getIntAttr(event, "time") >= time &&
            event.getAttributes.get("type") == ofType && event.getAttributes.get(attrName) == attrValue
          )
      }

      events should not be empty
      val rideHailWithPassengers = events.filter(event =>
        event.getAttributes.get("type") == "PathTraversal"
        && event.getAttributes.get("vehicle").startsWith("rideHailVehicle-")
        && getIntAttr(event, "numPassengers") > 0
      )

      rideHailWithPassengers should not be empty withClue "Expected to have ride hail PathTraversal with more than 0 passengers"
      forAll(rideHailWithPassengers) { rhPte =>
        val (start, end) = getStartEnd(rhPte)
        start should not be end withClue "RH leg shouldn't be between the same stop"
        start should (be(stop1) or be(stop2)) withClue "Start stop should be either of two pre-set from input file."
        end should (be(stop1) or be(stop2)) withClue "End stop should be either of two pre-set from input file."

        val riders = rhPte.getAttributes.get("riders").split(':').toList
        // in case of pooled RH trip with multiple RH legs this wouldn't work.
        // though in Beamville only short pooled trips are possible I hope
        riders should not be empty withClue "Expected to have riders in the PathTraversal event"
        forAll(riders) { rider =>
          // validate that previous walking start at the activity location
          val rhDepartureTime = getIntAttr(rhPte, "departureTime")

          // actEnd before the current trip
          val maybeActEnd = getEventBefore(rhDepartureTime, "actend", "person", rider)
          maybeActEnd should not be empty withClue f"Expected to have an ActEnd event for a person before RH departure. Person: $rider"
          val actEnd = maybeActEnd.get
          val actEndTime = getIntAttr(actEnd, "time")

          // actStart at the end of the current trip
          val maybeActStart = getEventAfter(rhDepartureTime, "actstart", "person", rider)
          maybeActStart should not be empty withClue f"Expected to have an ActStart event for a person some time after RH departure. Person: $rider"
          val actStart = maybeActStart.get

          // a walking PathTraversal event after the actEnd event
          val maybeWalkingBeforeRH = getEventAfter(actEndTime, "PathTraversal", "vehicle", s"body-$rider")
          maybeWalkingBeforeRH should not be empty withClue f"Expected to have a PathTraversal event before RH departure. Person: $rider"

          val walkingBeforeRH = maybeWalkingBeforeRH.get
          walkingBeforeRH.getAttributes.get(
            "mode"
          ) shouldBe "walk" withClue "Expected walk PathTraversal event after the actEnd and before RH trip"

          val firstWalkLink = walkingBeforeRH.getAttributes.get("links").split(',').head
          firstWalkLink shouldBe actEnd.getAttributes.get(
            "link"
          ) withClue "Expected walk event to start at the same link of actEnd"

          // validate that previous walking ends at the pickup stop
          val (walkStart1, walkEnd1) = getStartEnd(walkingBeforeRH)
          if (walkStart1 != walkEnd1) {
            walkEnd1 shouldBe start withClue "Expected walk event to end at pick-up location."
          } else {
            logger.warn(s"Router provided a walk route to RH stop with the same start/end for person $rider")
          }
          val rhArrivalTime = getIntAttr(rhPte, "arrivalTime")
          // validate that after RH walking ends at the activity location
          val walkingAfterRH = getEventAfter(rhArrivalTime, "PathTraversal", "vehicle", s"body-$rider").get
          walkingAfterRH.getAttributes.get("mode") shouldBe "walk"
          val pteLinks2 = walkingAfterRH.getAttributes.get("links").split(',')
          val lastLink = pteLinks2.last
          lastLink shouldBe actStart.getAttributes.get("link")
          // validate that after RH walking starts at the dropoff stop
          val (walkStart2, walkEnd2) = getStartEnd(walkingAfterRH)
          if (walkStart2 != walkEnd2) {
            walkStart2 shouldBe end
          } else {
            logger.warn(s"Router provided a walk route from RH stop with the same start/end for person $rider")
          }
        }
      }
    }
  }

  private def getIntAttr(event: Event, attr: String): Int =
    MathUtils.doubleToInt(event.getAttributes.get(attr).toDouble)

  private def getStartEnd(pathTraversalEvent: Event): (LooseCoord, LooseCoord) = {
    // vehicle is a string that looks like rideHailVehicle-5@GlobalRHM
    val startX = pathTraversalEvent.getAttributes.get("startX")
    val startY = pathTraversalEvent.getAttributes.get("startY")
    val endX = pathTraversalEvent.getAttributes.get("endX")
    val endY = pathTraversalEvent.getAttributes.get("endY")
    (LooseCoord(startX, startY, 2), LooseCoord(endX, endY, 2))
  }

}

final case class LooseCoord(x: BigDecimal, y: BigDecimal)

object LooseCoord {
  import scala.math.BigDecimal.RoundingMode

  def apply(coord: Coord, precision: Int): LooseCoord = {
    val x = BigDecimal(coord.getX.toString).setScale(precision, RoundingMode.HALF_UP)
    val y = BigDecimal(coord.getY.toString).setScale(precision, RoundingMode.HALF_UP)
    LooseCoord(x, y)
  }

  def apply(x: Double, y: Double, precision: Int): LooseCoord = {
    val x1 = BigDecimal(x.toString).setScale(precision, RoundingMode.HALF_UP)
    val y1 = BigDecimal(y.toString).setScale(precision, RoundingMode.HALF_UP)
    LooseCoord(x1, y1)
  }

  def apply(x: String, y: String, precision: Int): LooseCoord = {
    val x1 = BigDecimal(x).setScale(precision, RoundingMode.HALF_UP)
    val y1 = BigDecimal(y).setScale(precision, RoundingMode.HALF_UP)
    LooseCoord(x1, y1)
  }
}
