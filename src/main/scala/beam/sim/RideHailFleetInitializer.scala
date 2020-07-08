package beam.sim

import java.nio.file.{Files, Paths}

import beam.analysis.plots.GraphsStatsAgentSimEventsListener
import beam.sim.common.{GeoUtils, Range}
import beam.utils.OutputDataDescriptor
import beam.utils.csv.{CsvWriter, GenericCsvReader}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Coord
import org.matsim.core.controler.OutputDirectoryHierarchy

import scala.util.Try
import scala.util.control.NonFatal

object RideHailFleetInitializer extends OutputDataDescriptor with LazyLogging {

  val outputFileBaseName = "rideHailFleet"

  private[sim] def toRideHailAgentInputData(rec: java.util.Map[String, String]): RideHailAgentInputData = {
    val id = GenericCsvReader.getIfNotNull(rec, "id")
    val rideHailManagerId = GenericCsvReader.getIfNotNull(rec, "rideHailManagerId")
    val vehicleType = GenericCsvReader.getIfNotNull(rec, "vehicleType")
    val initialLocationX = GenericCsvReader.getIfNotNull(rec, "initialLocationX").toDouble
    val initialLocationY = GenericCsvReader.getIfNotNull(rec, "initialLocationY").toDouble
    val shifts = Option(rec.get("shifts"))
    val geofenceX = Option(rec.get("geofenceX")).map(_.toDouble)
    val geofenceY = Option(rec.get("geofenceY")).map(_.toDouble)
    val geofenceRadius = Option(rec.get("geofenceRadius")).map(_.toDouble)
    RideHailAgentInputData(
      id = id,
      rideHailManagerId = rideHailManagerId,
      vehicleType = vehicleType,
      initialLocationX = initialLocationX,
      initialLocationY = initialLocationY,
      shifts = shifts,
      geofenceX = geofenceX,
      geofenceY = geofenceY,
      geofenceRadius = geofenceRadius
    )
  }

  /**
    * A writer that writes the initialized fleet data to a csv on all iterations
    *
    * @param beamServices beam services isntance
    * @param fleetData data to be written
    */
  def writeFleetData(beamServices: BeamServices, fleetData: Seq[RideHailAgentInputData]): Unit = {
    try {
      val filePath = beamServices.matsimServices.getControlerIO
        .getIterationFilename(
          beamServices.matsimServices.getIterationNumber,
          RideHailFleetInitializer.outputFileBaseName + ".csv.gz"
        )
      writeFleetData(filePath, fleetData)
    } catch {
      case e: Exception =>
        logger.error("Error while writing procedurally initialized ride hail fleet data to csv ", e)
    }
  }

  def writeFleetData(filePath: String, fleetData: Seq[RideHailAgentInputData]): Unit = {
    val fileHeader: Array[String] = Array[String](
      "id",
      "rideHailManagerId",
      "vehicleType",
      "initialLocationX",
      "initialLocationY",
      "shifts",
      "geofenceX",
      "geofenceY",
      "geofenceRadius"
    )
    if (Files.exists(Paths.get(filePath).getParent)) {
      val csvWriter = new CsvWriter(filePath, fileHeader)
      Try {
        fleetData.foreach { fleetData =>
          csvWriter.write(
            fleetData.id,
            fleetData.rideHailManagerId,
            fleetData.vehicleType,
            fleetData.initialLocationX,
            fleetData.initialLocationY,
            fleetData.shifts.getOrElse(""),
            fleetData.geofenceX.getOrElse(""),
            fleetData.geofenceY.getOrElse(""),
            fleetData.geofenceRadius.getOrElse("")
          )
        }
      }
      csvWriter.close()
      logger.info(s"Fleet data with ${fleetData.size} entries is written to '$filePath'")
    }
  }

  /**
    * Reads the ride hail fleet csv as [[beam.agentsim.agents.ridehail.RideHailAgent]] objects
    *
    * @param filePath path to the csv file
    * @return list of [[beam.agentsim.agents.ridehail.RideHailAgent]] objects
    */
  def readFleetFromCSV(filePath: String): List[RideHailAgentInputData] = {
    // This is lazy, to make it to read the data we need to call `.toList`
    val (iter, toClose) = GenericCsvReader.readAs[RideHailAgentInputData](filePath, toRideHailAgentInputData, x => true)
    try {
      // Read the data
      val fleetData = iter.toList
      logger.info(s"Read fleet data with ${fleetData.size} entries from '$filePath'")
      fleetData
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Could not initialize fleet from '$filePath': ${ex.getMessage}", ex)
        List.empty
    } finally {
      toClose.close()
    }
  }

  /**
    * Generates Ranges from the range value as string
    *
    * @param rangesAsString ranges as string value
    * @return List of ranges
    */
  def generateRanges(rangesAsString: String): List[Range] = {
    val regex = """\{([0-9]+):([0-9]+)\}""".r
    rangesAsString.split(";").toList flatMap {
      case regex(l, u) =>
        try {
          Some(new Range(l.toInt, u.toInt))
        } catch {
          case _: Exception => None
        }
      case _ => None
    }
  }

  final val (
    attr_id,
    attr_rideHailManagerId,
    attr_vehicleType,
    attr_initialLocationX,
    attr_initialLocationY,
    attr_shifts,
    attr_geofenceX,
    attr_geofenceY,
    attr_geofenceRadius
  ) = (
    "id",
    "rideHailManagerId",
    "vehicleType",
    "initialLocationX",
    "initialLocationY",
    "shifts",
    "geofenceX",
    "geofenceY",
    "geofenceRadius"
  )

  /**
    * An intermediary class to hold the ride hail fleet data read from the file.
    *
    * @param id id of the vehicle
    * @param rideHailManagerId id of the ride hail manager
    * @param vehicleType type of the beam vehicle
    * @param initialLocationX x-coordinate of the initial location of the ride hail vehicle
    * @param initialLocationY y-coordinate of the initial location of the ride hail vehicle
    * @param shifts time shifts for the vehicle , usually a stringified collection of time ranges
    * @param geofenceX geo fence values
    * @param geofenceY geo fence values
    * @param geofenceRadius geo fence values
    */
  case class RideHailAgentInputData(
    id: String,
    rideHailManagerId: String,
    vehicleType: String,
    initialLocationX: Double,
    initialLocationY: Double,
    shifts: Option[String],
    geofenceX: Option[Double],
    geofenceY: Option[Double],
    geofenceRadius: Option[Double]
  ) {

    def toGeofence: Option[Geofence] = {
      if (geofenceX.isDefined && geofenceY.isDefined && geofenceRadius.isDefined) {
        Some(Geofence(geofenceX.get, geofenceY.get, geofenceRadius.get))
      } else {
        None
      }
    }

  }

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  override def getOutputDataDescriptions(
    ioController: OutputDirectoryHierarchy
  ): java.util.List[OutputDataDescription] = {
    val filePath = ioController.getIterationFilename(0, outputFileBaseName + ".csv.gz")
    val outputDirPath: String = ioController.getOutputPath
    val relativePath: String = filePath.replace(outputDirPath, "")
    val list: java.util.List[OutputDataDescription] = new java.util.ArrayList[OutputDataDescription]
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName.dropRight(1),
          relativePath,
          "id",
          "Id of the ride hail vehicle"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName.dropRight(1),
          relativePath,
          "rideHailManagerId",
          "Id of the ride hail manager"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName.dropRight(1),
          relativePath,
          "vehicleType",
          "Type of the beam vehicle"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName.dropRight(1),
          relativePath,
          "initialLocationX",
          "X-coordinate of the initial location of the ride hail vehicle"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName.dropRight(1),
          relativePath,
          "initialLocationY",
          "Y-coordinate of the initial location of the ride hail vehicle"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName.dropRight(1),
          relativePath,
          "shifts",
          "Time shifts for the vehicle , usually a stringified collection of time ranges"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName.dropRight(1),
          relativePath,
          "geoFenceX",
          "X-coordinate of the geo fence central point"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName.dropRight(1),
          relativePath,
          "geoFenceY",
          "Y-coordinate of the geo fence central point"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName.dropRight(1),
          relativePath,
          "geoFenceRadius",
          "Radius of the geo fence"
        )
      )
    list
  }

}

final case class Geofence(
  geofenceX: Double,
  geofenceY: Double,
  geofenceRadius: Double
) {

  /**
    * Check whether provided point inside Geofence
    *
    */
  def contains(x: Double, y: Double): Boolean = {
    val dist = GeoUtils.distFormula(geofenceX, geofenceY, x, y)
    dist <= geofenceRadius
  }

  def contains(coord: Coord): Boolean = contains(coord.getX, coord.getY)
}
