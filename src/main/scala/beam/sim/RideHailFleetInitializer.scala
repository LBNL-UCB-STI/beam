package beam.sim

import akka.actor.ActorRef
import beam.agentsim.agents.ridehail.{RideHailAgent, RideHailManager, RideHailVehicleId, Shift}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleCategory, VehicleManager}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.router.BeamRouter.Location
import beam.sim.RideHailFleetInitializer.RideHailAgentInitializer
import beam.sim.common.{GeoUtils, Range}
import beam.sim.vehicles.VehiclesAdjustment
import beam.utils.OutputDataDescriptor
import beam.utils.csv.{CsvWriter, GenericCsvReader}
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import com.google.inject.{Inject, Provider, ProvisionException}
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.math3.distribution.UniformRealDistribution
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.controler.OutputDirectoryHierarchy

import java.nio.file.{Files, Paths}
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.math.{max, min}
import scala.util.Random
import scala.util.control.NonFatal

object RideHailFleetInitializer extends OutputDataDescriptor with LazyLogging {
  type FleetId = String

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
    val geofenceTAZFile = Option(rec.get("geofenceTAZFile"))
    val geofenceTazIds = Option(rec.get("geofenceTAZFile")).map(readTazIdsFile)
    val fleetId = rec.getOrDefault("fleetId", "default")
    val initialStateOfCharge = rec.getOrDefault("initialStateOfCharge", "1.0").toDouble

    RideHailAgentInputData(
      id = id,
      rideHailManagerId = VehicleManager.createOrGetIdUsingUnique(rideHailManagerId, VehicleManager.BEAMRideHail),
      vehicleType = vehicleType,
      initialLocationX = initialLocationX,
      initialLocationY = initialLocationY,
      shiftsStr = shifts,
      geofenceX = geofenceX,
      geofenceY = geofenceY,
      geofenceRadius = geofenceRadius,
      geofenceTazs = geofenceTazIds,
      geofenceTAZFile = geofenceTAZFile,
      fleetId = fleetId,
      initialStateOfCharge = initialStateOfCharge
    )
  }

  private def readTazIdsFile(tazFilePath: String): Set[Id[TAZ]] = {
    val source = Source.fromFile(tazFilePath)
    val lines = source.getLines.toVector
    source.close()
    lines.tail.map(tazId => Id.create(tazId, classOf[TAZ])).toSet
  }

  /**
    * Writes the initialized fleet data to a CSV file in the iteration output directory.
    *
    * @param beamServices beam services instance.
    * @param fleetData data to be written.
    * @param outputFileName file name
    */
  def writeFleetData(
    beamServices: BeamServices,
    fleetData: Seq[RideHailAgentInputData],
    outputFileName: String
  ): Unit = {
    val filePath = beamServices.matsimServices.getControlerIO
      .getIterationFilename(
        beamServices.matsimServices.getIterationNumber,
        outputFileName
      )
    writeFleetData(filePath, fleetData)
  }

  /**
    * Writes the initialized fleet data to a CSV file.
    *
    * @param filePath path to the CSV file where the data should be written.
    * @param fleetData data to be written.
    */
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
      "geofenceRadius",
      "geofenceTAZFile",
      "fleetId",
      "initialStateOfCharge"
    )
    if (Files.exists(Paths.get(filePath).getParent)) {
      val csvWriter = new CsvWriter(filePath, fileHeader)

      try {
        fleetData.sortBy(_.id).foreach { fleetData =>
          csvWriter.write(
            fleetData.id,
            fleetData.rideHailManagerId,
            fleetData.vehicleType,
            fleetData.initialLocationX,
            fleetData.initialLocationY,
            fleetData.shiftsStr.getOrElse(""),
            fleetData.geofenceX.getOrElse(""),
            fleetData.geofenceY.getOrElse(""),
            fleetData.geofenceRadius.getOrElse(""),
            fleetData.geofenceTAZFile.getOrElse(""),
            fleetData.fleetId,
            fleetData.initialStateOfCharge
          )
        }

        logger.info(s"Fleet data with ${fleetData.size} entries is written to '$filePath'")
      } catch {
        case NonFatal(ex) =>
          logger.error(s"Could not write refueling fleet data to CSV '$filePath': ${ex.getMessage}", ex)
      } finally {
        csvWriter.close()
      }
    }
  }

  /**
    * Reads the ride hail fleet csv as [[RideHailAgentInputData]] objects
    *
    * @param filePath path to the csv file
    * @return list of [[RideHailAgentInputData]] objects
    */
  def readFleetFromCSV(filePath: String): List[RideHailAgentInputData] = {
    // This is lazy, to make it to read the data we need to call `.toList`
    val (iter, toClose) = GenericCsvReader.readAs[RideHailAgentInputData](filePath, toRideHailAgentInputData, _ => true)
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
    * @param shiftsStr time shifts for the vehicle , usually a stringified collection of time ranges
    * @param geofenceX geo fence values
    * @param geofenceY geo fence values
    * @param geofenceRadius geo fence values
    * @param fleetId ID of the fleet to which the vehicle belongs
    * @param initialStateOfCharge initial state of charge in interval [0.0,1.0]. Ignored for non-electric vehicles.
    */
  case class RideHailAgentInputData(
    id: String,
    rideHailManagerId: Id[VehicleManager],
    vehicleType: String,
    initialLocationX: Double,
    initialLocationY: Double,
    shiftsStr: Option[String],
    geofenceX: Option[Double],
    geofenceY: Option[Double],
    geofenceRadius: Option[Double],
    geofenceTazs: Option[Set[Id[TAZ]]],
    geofenceTAZFile: Option[String],
    fleetId: String,
    initialStateOfCharge: Double = 1.0
  ) {

    /*
     * If both a taz based geofence and a circular one are defined, the taz based takes precedence.
     */
    def geofence(tazTreeMap: TAZTreeMap): Option[Geofence] = {
      if (geofenceTazs.isDefined) {
        Some(TAZGeofence(geofenceTazs.get, tazTreeMap, geofenceTAZFile.get))
      } else if (geofenceX.isDefined && geofenceY.isDefined && geofenceRadius.isDefined) {
        Some(CircularGeofence(geofenceX.get, geofenceY.get, geofenceRadius.get))
      } else {
        None
      }
    }

    def initialLocation: Coord = {
      new Coord(initialLocationX, initialLocationY)
    }

    def createRideHailAgentInitializer(beamScenario: BeamScenario): RideHailAgentInitializer = {
      val beamVehicleType = beamScenario.vehicleTypes(Id.create(vehicleType, classOf[BeamVehicleType]))
      val shifts = shiftsListFromString(shiftsStr)

      RideHailAgentInitializer(
        id,
        beamVehicleType,
        rideHailManagerId,
        shifts,
        initialStateOfCharge,
        initialLocation,
        geofence(beamScenario.tazTreeMap),
        fleetId
      )
    }
  }

  def shiftsListFromString(shiftsStr: Option[String]): Option[List[Shift]] = {
    shiftsStr.map(_.split(";").map(Shift(_)).toList)
  }

  /**
    * Holds the data necessary to initialize a ride hail agent.
    *
    * This is a sister class to RideHailAgentInputData. RideHailAgentInputData serializes the data of this class to CSV.
    *
    * @param id ID of the ride hail agent. This should not include "rideHailAgent" or "rideHailVehicle". These are
    *           added automatically
    * @param beamVehicleType Type of the vehicle
    * @param rideHailManagerId ID of the ride hail manager
    * @param shifts Shift information
    * @param initialStateOfCharge Initial state of charge. Ignored for non-electric vehicles
    * @param initialLocation Initial location
    * @param geofence Geofence applying to the ride hail vehicle
    * @param fleetId ID of the fleet to which the vehicle belongs
    */
  case class RideHailAgentInitializer(
    id: String,
    beamVehicleType: BeamVehicleType,
    rideHailManagerId: Id[VehicleManager],
    shifts: Option[List[Shift]],
    initialStateOfCharge: Double,
    initialLocation: Coord,
    geofence: Option[Geofence],
    fleetId: String
  ) {
    val rideHailAgentId: Id[RideHailAgent] = Id.create(s"${RideHailAgent.idPrefix}-$id", classOf[RideHailAgent])
    val beamVehicleId: Id[BeamVehicle] = RideHailVehicleId(id, fleetId).beamVehicleId

    /**
      * Creates a BeamVehicle using the initialization data in the class.
      *
      * @param manager Ride Hail Manager Actor
      * @param randomSeed Random seed
      * @return Created BeamVehicle
      */
    def createBeamVehicle(manager: Option[ActorRef], randomSeed: Int = 0): BeamVehicle = {
      // Code taken from RideHailManager.createRideHailVehicleAndAgent

      val powertrain = new Powertrain(beamVehicleType.primaryFuelConsumptionInJoulePerMeter)

      val beamVehicle = new BeamVehicle(
        beamVehicleId,
        powertrain,
        beamVehicleType,
        vehicleManagerId = rideHailManagerId,
        randomSeed
      )

      beamVehicle.initializeFuelLevels(initialStateOfCharge)
      beamVehicle.spaceTime = SpaceTime((initialLocation, 0))
      beamVehicle.setManager(manager)

      beamVehicle
    }

    private def shiftsStr: Option[String] = {
      shifts match {
        case Some(shifts) => Some(shifts.map(_.toString()).mkString(";"))
        case None         => None
      }
    }

    /** Creates an instance of RideHailAgentInputData from the data in this instance */
    def createRideHailAgentInputData: RideHailAgentInputData = {

      val (geofenceCircularMaybe, geofenceTazMaybe) = geofence
        .map {
          case g: CircularGeofence => (Some(g), None)
          case g: TAZGeofence      => (None, Some(g))
        }
        .getOrElse((None, None))

      RideHailAgentInputData(
        id,
        rideHailManagerId,
        beamVehicleType.id.toString,
        initialLocation.getX,
        initialLocation.getY,
        shiftsStr,
        geofenceCircularMaybe.map(_.geofenceX),
        geofenceCircularMaybe.map(_.geofenceY),
        geofenceCircularMaybe.map(_.geofenceRadius),
        geofenceTazMaybe.map(_.tazs),
        geofenceTazMaybe.map(_.geofenceTAZFile),
        fleetId,
        initialStateOfCharge
      )
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
    val filePath = ioController
      .getIterationFilename(0, "rideHailFleetFromInitializer.csv.gz")
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

/**
  * Interface for algorithms that specify how the ride hail fleet should be initialized.
  *
  * This trait also supports overriding at runtime how the fleet should be initalzed in future iterations.
  */
trait RideHailFleetInitializer extends LazyLogging {
  private var rideHailAgentInitializersOpt: Option[IndexedSeq[RideHailAgentInitializer]] = None
  val linkFleetStateAcrossIterations = true

  /**
    * Returns a sequence of RideHailAgentInitializer that were generated using an initialization algorithm or through
    * overrideRideHailAgentInitializers.
    *
    * @param rideHailManagerId ID of the ride hail manager.
    * @param activityQuadTreeBounds Activty quad tree bounds, required by some initialization algorithms.
    * @return Sequence of RideHailAgentInitializer.
    */
  def getRideHailAgentInitializers(
    rideHailManagerId: Id[VehicleManager],
    activityQuadTreeBounds: QuadTreeBounds
  ): IndexedSeq[RideHailAgentInitializer] = {
    rideHailAgentInitializersOpt match {
      case Some(nextRideHailAgentInitializers) =>
        nextRideHailAgentInitializers
      case None =>
        val rideHailAgentInitializers = generateRideHailAgentInitializers(rideHailManagerId, activityQuadTreeBounds)
        rideHailAgentInitializersOpt = Some(rideHailAgentInitializers)
        rideHailAgentInitializers
    }
  }

  /**
    * Sets the sequence of RideHailAgentInitializer that should be returned by future calls to
    * getRideHailAgentInitializers
    */
  def overrideRideHailAgentInitializers(nextRideHailAgentInitializers: IndexedSeq[RideHailAgentInitializer]): Unit = {
    rideHailAgentInitializersOpt = Some(nextRideHailAgentInitializers)
  }

  /** Interface method to define initialization algorithms. */
  protected def generateRideHailAgentInitializers(
    rideHailManagerId: Id[VehicleManager],
    activityQuadTreeBounds: QuadTreeBounds
  ): IndexedSeq[RideHailAgentInitializer]
}

/**
  * Initializes the ride hail fleet by reading a file. See RideHailAgentInputData for format.
  *
  * @param beamServices BEAM services
  * @param beamScenario BEAM scenario
  */
class FileRideHailFleetInitializer(val beamServices: BeamServices, val beamScenario: BeamScenario)
    extends RideHailFleetInitializer {

  protected def generateRideHailAgentInitializers(
    rideHailManagerId: Id[VehicleManager],
    activityQuadTreeBounds: QuadTreeBounds
  ): IndexedSeq[RideHailAgentInitializer] = {
    val fleetFilePath = beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.filePath

    val rideHailInputDatas = RideHailFleetInitializer.readFleetFromCSV(fleetFilePath).toIndexedSeq
    rideHailInputDatas.map(_.createRideHailAgentInitializer(beamScenario))
  }
}

/**
  * Initializes the ride hail fleet through sampling.
  *
  * @param beamServices BEAM services
  * @param beamScenario BEAM scenario
  * @param scenario scenario
  */
class ProceduralRideHailFleetInitializer(
  val beamServices: BeamServices,
  val beamScenario: BeamScenario,
  val scenario: Scenario
) extends RideHailFleetInitializer {

  val rand: Random = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)
  val realDistribution: UniformRealDistribution = new UniformRealDistribution()
  realDistribution.reseedRandomGenerator(beamServices.beamConfig.matsim.modules.global.randomSeed)

  private def computeNumRideHailAgents: Long = {
    val fleet: Double = beamServices.beamConfig.beam.agentsim.agents.vehicles.fractionOfInitialVehicleFleet
    val initialNumHouseholdVehicles = scenario.getHouseholds.getHouseholds
      .values()
      .asScala
      .flatMap { hh =>
        hh.getVehicleIds.asScala.map { vehId =>
          beamScenario.privateVehicles
            .get(vehId)
            .map(_.beamVehicleType)
            .getOrElse(throw new IllegalStateException(s"$vehId is not found in `beamServices.privateVehicles`"))
        }
      }
      .count(beamVehicleType => beamVehicleType.vehicleCategory == VehicleCategory.Car) / fleet

    math.round(
      initialNumHouseholdVehicles *
      beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.fractionOfInitialVehicleFleet
    )
  }

  protected def generateRideHailAgentInitializers(
    rideHailManagerId: Id[VehicleManager],
    activityQuadTreeBounds: QuadTreeBounds
  ): IndexedSeq[RideHailAgentInitializer] = {
    val averageOnDutyHoursPerDay = 3.52 // Measured from Austin Data, assuming drivers took at least 4 trips
    val meanLogShiftDurationHours = 1.02
    val stdLogShiftDurationHours = 0.44
    var equivalentNumberOfDrivers = 0.0

    val personsWithMoreThanOneActivity =
      scenario.getPopulation.getPersons.values().asScala.filter(_.getSelectedPlan.getPlanElements.size > 1)
    val persons: Array[Person] = rand.shuffle(personsWithMoreThanOneActivity).toArray

    val activityEndTimes: Array[Int] = persons.flatMap {
      _.getSelectedPlan.getPlanElements.asScala
        .collect {
          case activity: Activity if activity.getEndTime.toInt > 0 => activity.getEndTime.toInt
        }
    }

    val vehiclesAdjustment = VehiclesAdjustment.getVehicleAdjustment(beamScenario)

    val rideHailAgentInitializers: ArrayBuffer[RideHailFleetInitializer.RideHailAgentInitializer] = new ArrayBuffer()
    var idx = 0
    val numRideHailAgents = computeNumRideHailAgents
    while (equivalentNumberOfDrivers < numRideHailAgents.toDouble) {
      if (idx >= persons.length) {
        logger.error(
          "Can't have more ridehail drivers than total population"
        )
      } else {
        try {
          val person = persons(idx)
          val vehicleType = vehiclesAdjustment
            .sampleVehicleTypes(
              numVehicles = 1,
              vehicleCategory = VehicleCategory.Car,
              realDistribution
            )
            .head
          if (
            beamServices.beamConfig.beam.agentsim.agents.rideHail.refuelThresholdInMeters >=
              (vehicleType.primaryFuelCapacityInJoule / vehicleType.primaryFuelConsumptionInJoulePerMeter) * 0.8
          ) {
            logger.error(
              "Ride Hail refuel threshold is higher than state of energy of a vehicle fueled by a DC fast charger. This will cause an infinite loop"
            )
          }
          val rideInitialLocation: Location = getRideInitLocation(person, activityQuadTreeBounds)

          val meanSoc = beamServices.beamConfig.beam.agentsim.agents.vehicles.meanRidehailVehicleStartingSOC
          val initialStateOfCharge = BeamVehicle.randomSocFromUniformDistribution(rand, vehicleType, meanSoc)

          val (shiftsOpt, shiftEquivalentNumberOfDrivers) = if (vehicleType.automationLevel >= 4) {
            (None, 1.0)
          } else {
            val shiftDuration =
              math.round(math.exp(rand.nextGaussian() * stdLogShiftDurationHours + meanLogShiftDurationHours) * 3600)
            val shiftMidPointTime = activityEndTimes(rand.nextInt(activityEndTimes.length))
            val shiftStartTime = max(shiftMidPointTime - (shiftDuration / 2).toInt, 10)
            val shiftEndTime = min(shiftMidPointTime + (shiftDuration / 2).toInt, 30 * 3600)

            val shiftEquivalentNumberOfDrivers_ = (shiftEndTime - shiftStartTime) / (averageOnDutyHoursPerDay * 3600)

            (Some(List(Shift(Range(shiftStartTime, shiftEndTime), None))), shiftEquivalentNumberOfDrivers_)
          }

          val rideHailAgentInitializer = RideHailAgentInitializer(
            person.getId.toString,
            vehicleType,
            rideHailManagerId,
            shiftsOpt,
            initialStateOfCharge,
            rideInitialLocation,
            geofence = None,
            fleetId = "default"
          )

          rideHailAgentInitializers += rideHailAgentInitializer

          equivalentNumberOfDrivers += shiftEquivalentNumberOfDrivers
        } catch {
          case ex: Throwable =>
            logger.error(s"Could not generate RideHailAgentInitializer: ${ex.getMessage}")
            throw ex
        }
        idx += 1
      }
    }

    rideHailAgentInitializers.toIndexedSeq
  }

  private def getRideInitLocation(person: Person, activityQuadTreeBounds: QuadTreeBounds): Location = {
    val rideInitialLocation: Location =
      beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.initialLocation.name match {
        case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_RANDOM_ACTIVITY =>
          val radius =
            beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.initialLocation.home.radiusInMeters
          val activityLocations: List[Location] =
            person.getSelectedPlan.getPlanElements.asScala
              .collect { case activity: Activity =>
                activity.getCoord
              }
              .toList
              .dropRight(1)
          val randomActivityLocation: Location = activityLocations(rand.nextInt(activityLocations.length))
          new Coord(
            randomActivityLocation.getX + radius * (rand.nextDouble() - 0.5),
            randomActivityLocation.getY + radius * (rand.nextDouble() - 0.5)
          )
        case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_HOME =>
          val personInitialLocation: Location =
            person.getSelectedPlan.getPlanElements
              .iterator()
              .next()
              .asInstanceOf[Activity]
              .getCoord
          val radius =
            beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.initialLocation.home.radiusInMeters
          new Coord(
            personInitialLocation.getX + radius * (rand.nextDouble() - 0.5),
            personInitialLocation.getY + radius * (rand.nextDouble() - 0.5)
          )
        case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_UNIFORM_RANDOM =>
          val x = activityQuadTreeBounds.minx + (activityQuadTreeBounds.maxx - activityQuadTreeBounds.minx) * rand
            .nextDouble()
          val y = activityQuadTreeBounds.miny + (activityQuadTreeBounds.maxy - activityQuadTreeBounds.miny) * rand
            .nextDouble()
          new Coord(x, y)
        case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_ALL_AT_CENTER =>
          val x = activityQuadTreeBounds.minx + (activityQuadTreeBounds.maxx - activityQuadTreeBounds.minx) / 2
          val y = activityQuadTreeBounds.miny + (activityQuadTreeBounds.maxy - activityQuadTreeBounds.miny) / 2
          new Coord(x, y)
        case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_ALL_IN_CORNER =>
          val x = activityQuadTreeBounds.minx
          val y = activityQuadTreeBounds.miny
          new Coord(x, y)
        case unknown =>
          logger.error(s"unknown rideHail.initialLocation $unknown, assuming HOME")
          val personInitialLocation: Location =
            person.getSelectedPlan.getPlanElements
              .iterator()
              .next()
              .asInstanceOf[Activity]
              .getCoord
          val radius =
            beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.initialLocation.home.radiusInMeters
          new Coord(
            personInitialLocation.getX + radius * (rand.nextDouble() - 0.5),
            personInitialLocation.getY + radius * (rand.nextDouble() - 0.5)
          )
      }
    rideInitialLocation
  }
}

/** Provider class for RideHailFleetInitializer */
class RideHailFleetInitializerProvider @Inject() (
  val beamServices: BeamServices,
  val beamScenario: BeamScenario,
  val scenario: Scenario
) extends Provider[RideHailFleetInitializer] {

  // Use lazy vals so that they persist across iterations
  private lazy val proceduralRideHailFleetInitializer =
    new ProceduralRideHailFleetInitializer(beamServices, beamScenario, scenario)
  private lazy val fileRideHailFleetInitializer = new FileRideHailFleetInitializer(beamServices, beamScenario)

  def get(): RideHailFleetInitializer = {
    beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.initType match {
      case "PROCEDURAL" =>
        proceduralRideHailFleetInitializer
      case "FILE" =>
        fileRideHailFleetInitializer
      case _ =>
        throw new ProvisionException(
          "Unidentified initialization type : " +
          beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization
        )
    }
  }
}

/**
  * Geofence defining the area, where a ride hail vehicle should stay
  */
trait Geofence {

  /**
    * Check whether provided point defined by x,y coordinates is inside Geofence
    */
  def contains(x: Double, y: Double): Boolean

  /**
    * Check whether provided coordinate is inside Geofence
    */
  def contains(coord: Coord): Boolean = contains(coord.getX, coord.getY)
}

/**
  * Circular Geofence defined by center coordinates of circle and radius
  */
case class CircularGeofence(
  geofenceX: Double,
  geofenceY: Double,
  geofenceRadius: Double
) extends Geofence {

  override def contains(x: Double, y: Double): Boolean = {
    val dist = GeoUtils.distFormula(geofenceX, geofenceY, x, y)
    dist <= geofenceRadius
  }

}

/**
  * Geofence defined by set of TAZ Ids
  */
case class TAZGeofence(
  tazs: Set[Id[TAZ]],
  tazTreeMap: TAZTreeMap,
  geofenceTAZFile: String
) extends Geofence {

  override def contains(x: Double, y: Double): Boolean = {
    tazs.contains(tazTreeMap.getTAZ(x, y).tazId)
  }

  override def toString() = {
    s"TAZGeofence(${tazs.size} tazs from file: $geofenceTAZFile)"
  }

}
