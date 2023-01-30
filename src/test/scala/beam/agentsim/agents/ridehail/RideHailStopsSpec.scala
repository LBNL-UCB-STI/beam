package beam.agentsim.agents.ridehail

import beam.sim.BeamHelper
import beam.utils.EventReader._
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.events.Event
import org.scalatest.Inspectors.forAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.math.MathContext

/**
  * @author Dmitry Openkov
  */
class RideHailStopsSpec extends AnyWordSpecLike with Matchers with BeamHelper {

  "RideHail stops feature" should {
    "RH vehicles move passengers only between the RH stops" in {
      // We define 2 stops only in file ridehail-stops.csv
      val stop1 = StrictCoord(0.04, 0.04, 2)
      val stop2 = StrictCoord(0.02, 0.0, 2)
      // All RH moves with passengers must be between these 2 locations
      val config = BeamHelper.updateConfigToCurrentVersion(
        ConfigFactory
          .parseString("""
              beam.cfg.copyRideHailToFirstManager = true
              beam.agentsim.agents.rideHail.stopFilePath="./test/test-resources/beam/input/ridehail-stops.csv"
              beam.agentsim.agents.rideHail.maximumWalkDistanceToStopInM=1600
              beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_intercept = 10
              beam.physsim.skipPhysSim = true
          """)
          .withFallback(testConfig("test/input/beamville/beam.conf"))
          .resolve()
      )

      val (matSimConfig, _, _) = runBeamWithConfig(config)

      val filePath = getEventsFilePath(matSimConfig, "events", "csv").getAbsolutePath
      val (eventIterator, closable) = fromCsvFile(
        filePath,
        event =>
          event.getEventType == "PathTraversal" && event.getAttributes.get("vehicle").startsWith("rideHailVehicle-")
      )
      val events = eventIterator.toList
      closable.close()
      events should not be empty
      val rideHailWithPassengers = events.filter(event => getNumberOfPassengers(event) > 0)
      rideHailWithPassengers should not be empty
      forAll(rideHailWithPassengers) { event =>
        val (start, end) = getStartEnd(event)
        start should not be end
        start should (be(stop1) or be(stop2))
        end should (be(stop1) or be(stop2))
      }
    }
  }

  private def getNumberOfPassengers(event: Event): Int = {
    event.getAttributes.get("numPassengers").toInt
  }

  private def getStartEnd(event: Event): (StrictCoord, StrictCoord) = {
    // vehicle is a string that looks like rideHailVehicle-5@GlobalRHM
    val startX = event.getAttributes.get("startX")
    val startY = event.getAttributes.get("startY")
    val endX = event.getAttributes.get("endX")
    val endY = event.getAttributes.get("endY")
    (StrictCoord(startX, startY, 2), StrictCoord(endX, endY, 2))
  }

}

final case class StrictCoord(x: BigDecimal, y: BigDecimal)

object StrictCoord {

  def apply(coord: Coord, precision: Int): StrictCoord = {
    val x = BigDecimal(coord.getX.toString, new MathContext(precision))
    val y = BigDecimal(coord.getY.toString, new MathContext(precision))
    StrictCoord(x, y)
  }

  def apply(x: Double, y: Double, precision: Int): StrictCoord = {
    val x1 = BigDecimal(x.toString, new MathContext(precision))
    val y1 = BigDecimal(y.toString, new MathContext(precision))
    StrictCoord(x1, y1)
  }

  def apply(x: String, y: String, precision: Int): StrictCoord = {
    val x1 = BigDecimal(x, new MathContext(precision))
    val y1 = BigDecimal(y, new MathContext(precision))
    StrictCoord(x1, y1)
  }
}
