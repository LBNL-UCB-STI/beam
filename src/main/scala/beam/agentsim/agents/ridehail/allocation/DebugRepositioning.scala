package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.ridehail.RideHailManager
import beam.utils.{PointToPlot, SpatialPlot}
import java.awt.Color

/**
  * BEAM
  */
class DebugRepositioning {}

object DebugRepositioning {

  def produceRepositioningDebugImages(tick: Int, rideHailManager: RideHailManager): Unit = {
    if (tick > 0 && tick % 3600 == 0 && tick < 24 * 3600) {
      val spatialPlot = new SpatialPlot(1100, 1100, 50)

      for (veh <- rideHailManager.resources.values) {
        spatialPlot.addPoint(
          PointToPlot(
            rideHailManager.rideHailManagerHelper.getRideHailAgentLocation(veh.id).latestUpdatedLocationUTM.loc,
            Color.BLACK,
            5
          )
        )
      }

      rideHailManager.tncIterationStats.foreach(tncIterationStats => {

        val tazEntries = tncIterationStats getCoordinatesWithRideHailStatsEntry (tick, tick + 3600)

        for (tazEntry <- tazEntries.filter(x => x._2.sumOfRequestedRides > 0)) {
          spatialPlot.addPoint(
            PointToPlot(
              tazEntry._1,
              Color.RED,
              10 + Math.log(tazEntry._2.sumOfRequestedRides).toInt
            )
          )
        }
      })

      val iteration = "it." + rideHailManager.beamServices.matsimServices.getIterationNumber
      spatialPlot.writeImage(
        rideHailManager.beamServices.matsimServices.getControlerIO
          .getIterationFilename(
            rideHailManager.beamServices.matsimServices.getIterationNumber,
            tick / 3600 + "locationOfAgentsInitally.png"
          )
          .replace(iteration, iteration + "/rideHailDebugging")
      )
    }
  }
}
