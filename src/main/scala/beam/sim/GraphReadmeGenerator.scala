package beam.sim

import java.io.{BufferedWriter, FileWriter, IOException}

/**
  * Generates a readme for .png files in root folder.
  */

object GraphReadmeGenerator {

  private val fileName = "graph_readme.txt"

  private val content =
    """averageCarSpeed.png - Average car speeds in meters per second, grouped by car type, per iteration.
      |
      |averageCarTravelTimes.png - Average travel time, using car, in minutes, including or excluding walk.
      |
      |delayAveragePerKilometer.png - Average delay intensity, in seconds per kilometer, per iteration.
      |
      |delayTotalByLinkCapacity.png - Total delay by binned link capacity, in hours, per iteration.
      |
      |modeChoice.png - Number of transportation chooses, distributed by modes, per iteration.
      |
      |realizedModeChoice.png - ???
      |
      |referenceModeChoice.png - ???
      |
      |referenceRealizedModeChoice.png - ???
      |
      |replanningCountModeChoice.png - Replanning event count per iteration.
      |
      |rideHailRevenue.png - Ride hail revenue in dollars, per iteration.
      |
      |rideHailUtilisation.png - Ride hail trip number, distributed with number of passengers, per iteration.
      |
      |scorestats.png - Simulation scores per iteration
      |
      |stopwatch.png - Computation time distribution per iteration
      """.stripMargin

  def generateGraphReadme(rootFolder: String): Unit = {

    //Generate graph comparison html element and write it to the html page at desired location
    val bw = new BufferedWriter(new FileWriter(rootFolder + "/" + fileName))
    try {
      bw.write(content)
    } catch {
      case e: IOException =>
        e.printStackTrace()
    } finally {
      bw.close()
    }
  }

}
