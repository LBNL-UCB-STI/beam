package scripts

import beam.agentsim.infrastructure.taz
import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.sim.common.GeoUtils
import beam.utils.CloseableUtil.RichCloseable
import beam.utils.FileUtils
import org.matsim.api.core.v01.Coord

import java.io.{BufferedReader, FileWriter}

/**
  * A script to add activity location TAZ to generatedPlans
  */
object AddTAZIdToGeneratedPlans {

  def addTazIdToActivityRow(activityRow: String, convertCoord: Coord => Coord, getTaz: Coord => String): String = {
    val values = activityRow.split(',')
    val rowType = values(5)
    val tazId = rowType match {
      case "activity" =>
        val activityCoord: Coord = {
          val x = values(8).toDouble
          val y = values(9).toDouble
          convertCoord(new Coord(x, y))
        }
        getTaz(activityCoord)
      case _ => ""
    }
    val rowWithTAZ = activityRow + "," + tazId
    rowWithTAZ
  }

  def addTAZIdToActivitiesLocations(
    generatedPlansFilePath: String,
    tazCentersFilePath: String,
    maybeCRS: Option[String]
  ): Seq[String] = {
    def convertCoord: Coord => Coord = {
      maybeCRS match {
        case Some(crs) =>
          val geoUtils = new GeoUtils { override def localCRS: String = crs }
          (coord: Coord) => geoUtils.wgs2Utm(coord)
        case None => (coord: Coord) => coord
      }
    }

    val tazTreeMap: TAZTreeMap = taz.TAZTreeMap.getTazTreeMap(tazCentersFilePath)
    def getTaz(coord: Coord): String = {
      tazTreeMap.getTAZ(coord).tazId.toString
    }

    val bufferedReader: BufferedReader = FileUtils.readerFromFile(generatedPlansFilePath)
    val headerWithTAZ = bufferedReader.readLine() + ",activityLocationTAZ"
    val plansRowsWithTAZIds = Iterator
      .continually(bufferedReader.readLine())
      .takeWhile(_ != null)
      .map(fileRow => addTazIdToActivityRow(fileRow, convertCoord, getTaz))

    Seq(headerWithTAZ) ++ plansRowsWithTAZIds.toSeq
  }

  def main(args: Array[String]): Unit = {
    if (args.length < 4) {
      println(
        "Following arguments expected: <path to generated plans>  <path to TAZ centers file> <output path to generated plans with TAZ ids>"
      )
      println("Following arguments at the end are optional: [<crs of activities locations used to convert into WGS>]")
      println()
      println(s"Following arguments given: (len: ${args.length}) ${args.mkString(", ")}")
    } else {

      val pathToPlans = args(0)
      val pathToTAZ = args(1)
      val outputPath = args(2)
      val maybeCRS = if (args.length > 3) Some(args(3)) else None

      val fileRows = addTAZIdToActivitiesLocations(pathToPlans, pathToTAZ, maybeCRS)

      FileUtils.writeToFile(outputPath, Some(fileRows.head), fileRows.tail.mkString("\n"), None)
    }
  }
}
