package beam.sim

import java.io.FileNotFoundException

import beam.agentsim.agents.choice.mode.Range
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.analysis.plots.GraphsStatsAgentSimEventsListener
import beam.sim.RideHailFleetInitializer.FleetData
import beam.utils.{FileUtils, OutputDataDescriptor}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id

import scala.io.Source

class RideHailFleetInitializer extends LazyLogging {

  /**
    * Initializes [[beam.agentsim.agents.ridehail.RideHailAgent]] fleet
    * @param beamServices beam services instance
    * @return list of [[beam.agentsim.agents.ridehail.RideHailAgent]] objects
    */
  def init(
    beamServices: BeamServices
  ): List[(FleetData, BeamVehicle)] = {
    val filePath = beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.filename
    readCSVAsRideHailAgent(
      filePath,
      beamServices
    )
  }

  /**
    * Reads the ride hail fleet csv as [[beam.agentsim.agents.ridehail.RideHailAgent]] objects
    * @param filePath path to the csv file
    * @return list of [[beam.agentsim.agents.ridehail.RideHailAgent]] objects
    */
  private def readCSVAsRideHailAgent(
    filePath: String,
    beamServices: BeamServices
  ): List[(FleetData, BeamVehicle)] = {
    try {
      val bufferedSource = Source.fromFile(filePath)
      val rideHailAgents: Seq[(FleetData, BeamVehicle)] =
        bufferedSource.getLines().toList.drop(1).map(s => s.split(",")).flatMap { row =>
          try {
            val fleetData = FleetData(
              row(0),
              row(1),
              row(2),
              if (row(3).isEmpty) 0.0 else row(3).toDouble,
              if (row(4).isEmpty) 0.0 else row(4).toDouble,
              if (row(5).isEmpty) None else Some(row(5)),
              if (row(6).isEmpty) None else Some(row(6).toDouble),
              if (row(7).isEmpty) None else Some(row(7).toDouble),
              if (row(8).isEmpty) None else Some(row(8).toDouble)
            )
            val vehicleTypeId = Id.create(fleetData.vehicleType, classOf[BeamVehicleType])
            val vehicleType =
              beamServices.vehicleTypes.getOrElse(vehicleTypeId, BeamVehicleType.defaultCarBeamVehicleType)
            val powertrain = Option(vehicleType.primaryFuelConsumptionInJoulePerMeter)
              .map(new Powertrain(_))
              .getOrElse(Powertrain.PowertrainFromMilesPerGallon(Powertrain.AverageMilesPerGallon))
            val beamVehicle = new BeamVehicle(
              Id.create(fleetData.id, classOf[BeamVehicle]),
              powertrain,
              None,
              vehicleType
            )
            Some(fleetData -> beamVehicle)
          } catch {
            case e: Exception =>
              logger.error("Error while reading an entry of ride-hail-fleet.csv as RideHailAgent : " + e.getMessage, e)
              None
          }
        }
      rideHailAgents.toList
    } catch {
      case fne: FileNotFoundException =>
        logger.error(s"No file found at path - $filePath", fne)
        List.empty[(FleetData, BeamVehicle)]
      case e: Exception =>
        logger.error("Error while reading an entry of ride-hail-fleet.csv as RideHailAgent : " + e.getMessage, e)
        List.empty[(FleetData, BeamVehicle)]
    }
  }

  /**
    * A writer that writes the initialized fleet data to a csv on all iterations
    * @param beamServices beam services isntance
    * @param fleetData data to be written
    */
  def writeFleetData(beamServices: BeamServices, fleetData: List[FleetData]): Unit = {
    try {
      val filePath = beamServices.matsimServices.getControlerIO
        .getIterationFilename(beamServices.iterationNumber, RideHailFleetInitializer.outputFileBaseName + ".csv.gz")
      val fileHeader = classOf[FleetData].getDeclaredFields.map(_.getName).mkString(", ")
      val data = fleetData map { f =>
        f.productIterator.map(f => if (f == None) "" else f) mkString ", "
      } mkString "\n"
      FileUtils.writeToFile(filePath, Some(fileHeader), data, None)
    } catch {
      case e: Exception =>
        logger.error("Error while writing procedurally initialized ride hail fleet data to csv ", e)
    }
  }

}

object RideHailFleetInitializer extends OutputDataDescriptor {

  val outputFileBaseName = "rideHailFleet"

  /**
    * Generates Ranges from the range value as string
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

  /**
    * An intermediary class to hold the ride hail fleet data read from the file.
    * @param id id of the vehicle
    * @param rideHailManagerId id of the ride hail manager
    * @param vehicleType type of the beam vehicle
    * @param initialLocationX x-coordinate of the initial location of the ride hail vehicle
    * @param initialLocationY y-coordinate of the initial location of the ride hail vehicle
    * @param shifts time shifts for the vehicle , usually a stringified collection of time ranges
    * @param geoFenceX x-coordinate of the geo fence
    * @param geoFenceY x-coordinate of the geo fence
    * @param geoFenceRadius radius of the geo fence
    */
  case class FleetData(
    id: String,
    rideHailManagerId: String,
    vehicleType: String,
    initialLocationX: Double,
    initialLocationY: Double,
    shifts: Option[String],
    geoFenceX: Option[Double],
    geoFenceY: Option[Double],
    geoFenceRadius: Option[Double]
  )

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  override def getOutputDataDescriptions: java.util.List[OutputDataDescription] = {
    val filePath = GraphsStatsAgentSimEventsListener.CONTROLLER_IO
      .getIterationFilename(0, outputFileBaseName + ".csv.gz")
    val outputDirPath: String = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputPath
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
