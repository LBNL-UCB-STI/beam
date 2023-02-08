package beam.agentsim.agents.ridehail

import beam.sim.BeamHelper
import beam.utils.EventReader._
import beam.utils.TestConfigUtils.testConfig
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.events.Event
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
  * @author Dmitry Openkov
  */
class RideHailGeofenceSpec extends AnyWordSpecLike with Matchers with BeamHelper {

  "RideHail geofence feature" should {
    "keep RH vehicles in certain area" in {
      // this scenario contains 10 RH vehicles (1-10)
      // the first 5 ones are assigned to TAZes #1,2, the last 5 ones are assigned to TAZes #3,4
      // Beamville TAZes are placed in the following way:
      // 2 4
      // 1 3
      // We need to verify that the vehicles are not go outside of these rectangles
      val config = BeamHelper.updateConfigToCurrentVersion(
        testConfig("test/input/beamville/beam-rh-taz-fenced.conf").resolve()
      )

      val (matSimConfig, _, _) = runBeamWithConfig(config)

      val filePath = getEventsFilePath(matSimConfig, "events", "csv.gz").getAbsolutePath
      val (eventIterator, closable) = fromCsvFile(
        filePath,
        event =>
          event.getEventType == "PathTraversal" && event.getAttributes.get("vehicle").startsWith("rideHailVehicle-")
      )
      val events = eventIterator.toList
      events should not be empty
      closable.close()
      val groupedByVehicleId = events.groupBy(event => if (getRideHailVehicleId(event) <= 5) "left" else "right")
      for {
        leftEvents <- groupedByVehicleId.get("left").toSeq
        event      <- leftEvents
      } yield {
        val (start, end) = getStartEnd(event)
        println(s"left: ${getRideHailVehicleId(event)} travels $start -> $end")
        start.getX should be < 0.021
        end.getX should be < 0.021
      }
      for {
        leftEvents <- groupedByVehicleId.get("right").toSeq
        event      <- leftEvents
      } yield {
        val (start, end) = getStartEnd(event)
        println(s"right: ${getRideHailVehicleId(event)} travels $start -> $end")
        start.getX should be > 0.019
        end.getX should be > 0.019
      }

    }
  }

  private def getRideHailVehicleId(event: Event): Int = {
    // vehicle is a string that looks like rideHailVehicle-5@GlobalRHM
    event.getAttributes.get("vehicle").filter(_.isDigit).toInt
  }

  private def getStartEnd(event: Event): (Coord, Coord) = {
    // vehicle is a string that looks like rideHailVehicle-5@GlobalRHM
    val startX = event.getAttributes.get("startX").toDouble
    val startY = event.getAttributes.get("startY").toDouble
    val endX = event.getAttributes.get("endX").toDouble
    val endY = event.getAttributes.get("endY").toDouble
    (new Coord(startX, startY), new Coord(endX, endY))
  }

}
