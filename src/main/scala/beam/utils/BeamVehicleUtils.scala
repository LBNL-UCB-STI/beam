package beam.utils

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.FuelType.{Electricity, FuelType}
import beam.agentsim.agents.vehicles._
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.sim.common.{DoubleTypedRange, Range}
import beam.sim.config.BeamConfig
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import com.typesafe.scalalogging.LazyLogging
import com.univocity.parsers.common.record.Record
import com.univocity.parsers.csv.CsvParser
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.io.IOUtils
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import java.util
import java.util.concurrent.atomic.AtomicReference
import scala.util.Random

object BeamVehicleUtils extends LazyLogging {

  def readVehiclesFile(
    filePath: String,
    vehiclesTypeMap: scala.collection.Map[Id[BeamVehicleType], BeamVehicleType],
    randomSeed: Long,
    vehicleManagerId: Id[VehicleManager]
  ): (Map[Id[BeamVehicle], BeamVehicle], Map[Id[BeamVehicle], Double]) = {
    val rand: Random = new Random(randomSeed)

    readCsvFileByLine(filePath, (Map.empty[Id[BeamVehicle], BeamVehicle], Map.empty[Id[BeamVehicle], Double])) {
      case (line, (vehicleAcc, socAcc)) =>
        val vehicleIdString = line.get("vehicleId")
        val vehicleId = Id.create(vehicleIdString, classOf[BeamVehicle])

        val vehicleTypeIdString = line.get("vehicleTypeId")
        val vehicleType = vehiclesTypeMap(Id.create(vehicleTypeIdString, classOf[BeamVehicleType]))

        val powerTrain = new Powertrain(vehicleType.primaryFuelConsumptionInJoulePerMeter)

        val beamVehicle =
          new BeamVehicle(
            vehicleId,
            powerTrain,
            vehicleType,
            new AtomicReference(vehicleManagerId),
            randomSeed = rand.nextInt
          )

        val initialSocStr = Option(line.get("stateOfCharge")).map(_.trim).getOrElse("")
        (
          vehicleAcc + (vehicleId -> beamVehicle),
          if (initialSocStr.isEmpty) socAcc else socAcc + (vehicleId -> initialSocStr.toDouble)
        )
    }
  }

  def readFuelTypeFile(filePath: String): scala.collection.Map[FuelType, Double] = {
    readCsvFileByLine(filePath, scala.collection.mutable.HashMap[FuelType, Double]()) { case (line, z) =>
      val fuelType = FuelType.fromString(line.get("fuelTypeId"))
      val priceInDollarsPerMJoule = line.get("priceInDollarsPerMJoule").toDouble
      z += ((fuelType, priceInDollarsPerMJoule))
    }
  }

  /**
    * These are fallback values. One should define the vehicle weight in the vehicleTypes.csv.
    * Column name is curbWeightInKg
    * @param vehicleCategory the vehicle category
    * @return an average curb weight of a vehicle that belongs to the provided category (in kg)
    */
  private def vehicleCategoryToWeightInKg(vehicleCategory: VehicleCategory.VehicleCategory): Double =
    vehicleCategory match {
      case VehicleCategory.Body                 => 70
      case VehicleCategory.Bike                 => 80
      case VehicleCategory.Car                  => 2000 // Class 1&2a (GVWR <= 8500 lbs.)
      case VehicleCategory.MediumDutyPassenger  => 2500
      case VehicleCategory.LightHeavyDutyTruck  => 3500 // Class 2b&3 (GVWR 8501-14000 lbs.)
      case VehicleCategory.MediumHeavyDutyTruck => 5500 // Class 4-6 (GVWR 14001-26000 lbs.)
      case VehicleCategory.HeavyHeavyDutyTruck  => 10500 // CLass 7&8 (GVWR 26001 to >33,001 lbs.)
    }

  def readBeamVehicleTypeFile(filePath: String): Map[Id[BeamVehicleType], BeamVehicleType] = {
    readCsvFileByLine(filePath, scala.collection.mutable.HashMap[Id[BeamVehicleType], BeamVehicleType]()) {
      case (line: util.Map[String, String], z) =>
        val vehicleTypeId = Id.create(line.get("vehicleTypeId"), classOf[BeamVehicleType])
        val seatingCapacity = line.get("seatingCapacity").trim.toInt
        val standingRoomCapacity = line.get("standingRoomCapacity").trim.toInt
        val lengthInMeter = line.get("lengthInMeter").trim.toDouble
        val primaryFuelTypeId = line.get("primaryFuelType")
        val primaryFuelType = FuelType.fromString(primaryFuelTypeId)
        val primaryFuelConsumptionInJoulePerMeter = line.get("primaryFuelConsumptionInJoulePerMeter").trim.toDouble
        val primaryFuelCapacityInJoule = line.get("primaryFuelCapacityInJoule").trim.toDouble
        val primaryVehicleEnergyFile = Option(line.get("primaryVehicleEnergyFile"))
        val monetaryCostPerMeter: Double = Option(line.get("monetaryCostPerMeter")).map(_.toDouble).getOrElse(0d)
        val monetaryCostPerSecond: Double = Option(line.get("monetaryCostPerSecond")).map(_.toDouble).getOrElse(0d)
        val secondaryFuelTypeId = Option(line.get("secondaryFuelType"))
        val secondaryFuelType = secondaryFuelTypeId.map(FuelType.fromString)
        val secondaryFuelConsumptionInJoule =
          Option(line.get("secondaryFuelConsumptionInJoulePerMeter")).map(_.toDouble)
        val secondaryFuelCapacityInJoule = Option(line.get("secondaryFuelCapacityInJoule")).map(_.toDouble)
        val secondaryVehicleEnergyFile = Option(line.get("secondaryVehicleEnergyFile"))
        val automationLevel = Option(line.get("automationLevel")).map(_.toInt).getOrElse(1)
        val maxVelocity = Option(line.get("maxVelocity")).map(_.toDouble)
        val passengerCarUnit = Option(line.get("passengerCarUnit")).map(_.toDouble).getOrElse(1d)
        val rechargeLevel2RateLimitInWatts = Option(line.get("rechargeLevel2RateLimitInWatts")).map(_.toDouble)
        val rechargeLevel3RateLimitInWatts = Option(line.get("rechargeLevel3RateLimitInWatts")).map(_.toDouble)
        val vehicleCategory = VehicleCategory.fromString(line.get("vehicleCategory"))
        val curbWeight: Double = Option(line.get("curbWeightInKg"))
          .map(_.toDouble)
          .getOrElse(vehicleCategoryToWeightInKg(vehicleCategory))
        val sampleProbabilityWithinCategory =
          Option(line.get("sampleProbabilityWithinCategory")).map(_.toDouble).getOrElse(1.0)
        val sampleProbabilityString = Option(line.get("sampleProbabilityString"))
        val chargingCapability = Option(line.get("chargingCapability")).flatMap(ChargingPointType(_))
        val payloadCapacity = Option(line.get("payloadCapacityInKg")).map(_.toDouble)
        val wheelchairAccessible = Option(line.get("wheelchairAccessible")).map(_.toBoolean)
        val restrictRoadsByFreeSpeed = Option(line.get("restrictRoadsByFreeSpeedInMeterPerSecond")).map(_.toDouble)
        val emissionsRatesInGramsPerMile =
          Option(line.get("emissionsRatesInGramsPerMile"))
            .map(parseEmissionsString(_, vehicleTypeId))
            .getOrElse(Map.empty)
        val emissionsRatesFile = Option(line.get("emissionsRatesFile"))

        val bvt = BeamVehicleType(
          vehicleTypeId,
          seatingCapacity,
          standingRoomCapacity,
          lengthInMeter,
          curbWeight,
          primaryFuelType,
          primaryFuelConsumptionInJoulePerMeter,
          primaryFuelCapacityInJoule,
          monetaryCostPerMeter,
          monetaryCostPerSecond,
          secondaryFuelType,
          secondaryFuelConsumptionInJoule,
          secondaryFuelCapacityInJoule,
          automationLevel,
          maxVelocity,
          passengerCarUnit,
          rechargeLevel2RateLimitInWatts,
          rechargeLevel3RateLimitInWatts,
          vehicleCategory,
          primaryVehicleEnergyFile,
          secondaryVehicleEnergyFile,
          sampleProbabilityWithinCategory,
          sampleProbabilityString,
          chargingCapability,
          payloadCapacity,
          wheelchairAccessible,
          restrictRoadsByFreeSpeed,
          emissionsRatesInGramsPerMile,
          emissionsRatesFile
        )
        z += ((vehicleTypeId, bvt))
    }.toMap
  }

  /**
    * @param beamConfig BEAM Config
    * @return
    */
  def readBeamVehicleTypeFile(beamConfig: BeamConfig): Map[Id[BeamVehicleType], BeamVehicleType] = {
    val vehicleTypes = readBeamVehicleTypeFile(
      beamConfig.beam.agentsim.agents.vehicles.vehicleTypesFilePath
    ) ++ beamConfig.beam.agentsim.agents.freight.vehicleTypesFilePath.map(readBeamVehicleTypeFile).getOrElse(Map.empty)
    val rideHailTypeIds =
      beamConfig.beam.agentsim.agents.rideHail.managers.map(_.initialization.procedural.vehicleTypeId)
    val dummySharedCarId = beamConfig.beam.agentsim.agents.vehicles.dummySharedCar.vehicleTypeId
    val defaultVehicleType = BeamVehicleType(
      id = Id.create("DefaultVehicleType", classOf[BeamVehicleType]),
      seatingCapacity = 4,
      standingRoomCapacity = 0,
      lengthInMeter = 4.5,
      curbWeightInKg = 2000,
      primaryFuelType = FuelType.Gasoline,
      primaryFuelConsumptionInJoulePerMeter = 3655.98,
      primaryFuelCapacityInJoule = 3655980000.0,
      vehicleCategory = VehicleCategory.Car
    )

    val missingTypes = (dummySharedCarId.createId[BeamVehicleType] +: rideHailTypeIds.map(_.createId[BeamVehicleType]))
      .collect {
        case vehicleId if !vehicleTypes.contains(vehicleId) => vehicleId -> defaultVehicleType.copy(id = vehicleId)
      }
    vehicleTypes ++ missingTypes
  }

  /**
    * Reads a CSV file line by line and processes each line with a provided function.
    *
    * @param filePath The path to the CSV file.
    * @param z The initial value for the result accumulator.
    * @param readLine A function that processes each line of the CSV. It takes a map representing a CSV
    *                 line and the current state of the accumulator, and returns the updated state of the accumulator.
    * @tparam A The type of the accumulator/result.
    * @return The final state of the accumulator after processing all lines.
    */
  def readCsvFileByLine[A](filePath: String, z: A)(readLine: (java.util.Map[String, String], A) => A): A = {
    FileUtils.using(new CsvMapReader(FileUtils.readerFromFile(filePath), CsvPreference.STANDARD_PREFERENCE)) {
      mapReader =>
        var res: A = z
        val header = mapReader.getHeader(true)
        var line: java.util.Map[String, String] = mapReader.read(header: _*)
        while (null != line) {
          res = readLine(line, res)
          line = mapReader.read(header: _*)
        }
        res
    }
  }

  /**
    * loadLinkIdToGradeMapFromCSV
    * @param csvParser CSV File parser
    * @param beamConfig BEAM config
    * @return
    */
  def loadLinkIdToGradeMapFromCSV(csvParser: CsvParser, beamConfig: BeamConfig): Map[Int, Double] = {
    import scala.collection.JavaConverters._
    val linkIdHeader = "id"
    val gradeHeader = "average_gradient_percent"
    val filePath = beamConfig.beam.agentsim.agents.vehicles.linkToGradePercentFilePath
    val records: Iterable[Record] = filePath match {
      case "" =>
        List[Record]()
      case _ =>
        csvParser.iterateRecords(IOUtils.getBufferedReader(filePath)).asScala
    }
    records
      .map(csvRecord => {
        val linkId = csvRecord.getInt(linkIdHeader)
        val gradePercent = csvRecord.getDouble(gradeHeader)
        linkId.toInt -> gradePercent.toDouble
      })
      .toMap
  }

  /**
    * @param rand random number generator
    * @param beamVehicleType vehicle type
    * @param meanSoc average state of charge
    * @return
    */
  def randomSocFromUniformDistribution(rand: Random, beamVehicleType: BeamVehicleType, meanSoc: Double): Double = {
    beamVehicleType.primaryFuelType match {
      case Electricity =>
        val meanSOC = math.max(math.min(meanSoc, 1.0), 0.5)
        val minimumSOC = 2.0 * meanSOC - 1
        minimumSOC + (1.0 - minimumSOC) * rand.nextDouble()
      case _ => 1.0
    }
  }

  /**
    * Function to parse the emissions string
    * @param emissionString from vehicle types
    * @param vehicleTypeId vehicle type id
    * @return
    */
  private def parseEmissionsString(
    emissionString: String,
    vehicleTypeId: Id[BeamVehicleType]
  ): VehicleEmissions.EmissionsProfile.EmissionsProfile = {
    import VehicleEmissions.EmissionsRates._
    import scala.util.Try

    val sourcePattern = """(\w+)\(([^)]+)\)""".r

    emissionString
      .split(";")
      .flatMap {
        case sourcePattern(source, emissions) =>
          val emissionMap = emissions
            .split(",")
            .flatMap { emission =>
              val parts = emission.split(":").map(_.trim)
              if (parts.length == 2) {
                Some((parts(0).toLowerCase, Try(parts(1).toDouble).getOrElse(0.0)))
              } else if (parts.length == 1) {
                Some((parts(0).toLowerCase, 0.0))
              } else if (parts.length == 0) {
                None
              } else {
                logger.error(
                  s"Failure to process this emission source $source with emissions $emissions " +
                  s"from emissionsRatesInGramsPerMile corresponding to vehicle types Id ${vehicleTypeId.toString} "
                )
                None
              }
            }
            .toMap

          val emissionsRates = VehicleEmissions.EmissionsRates(
            emissionMap.getOrElse(_CH4.toLowerCase, 0.0),
            emissionMap.getOrElse(_CO.toLowerCase, 0.0),
            emissionMap.getOrElse(_CO2.toLowerCase, 0.0),
            emissionMap.getOrElse(_HC.toLowerCase, 0.0),
            emissionMap.getOrElse(_NH3.toLowerCase, 0.0),
            emissionMap.getOrElse(_NOx.toLowerCase, 0.0),
            emissionMap.getOrElse(_PM.toLowerCase, 0.0),
            emissionMap.getOrElse(_PM10.toLowerCase, 0.0),
            emissionMap.getOrElse(_PM2_5.toLowerCase, 0.0),
            emissionMap.getOrElse(_ROG.toLowerCase, 0.0),
            emissionMap.getOrElse(_SOx.toLowerCase, 0.0),
            emissionMap.getOrElse(_TOG.toLowerCase, 0.0)
          )

          Some((VehicleEmissions.EmissionsProcesses.fromString(source), emissionsRates))

        case _ => None
      }
      .toMap
  }

  /**
    * @param energyInJoule Joules
    * @param durationInSecond Seconds
    * @return KW
    */
  def toPowerInKW(energyInJoule: Double, durationInSecond: Int): Double = {
    if (durationInSecond > 0 && energyInJoule >= 0) (energyInJoule / 3.6e+6) / (durationInSecond / 3600.0)
    else 0
  }

  def convertRecordStringToRange(recordString: String): Range =
    Range(recordString.replace(",", ":").replace(" ", ""))

  def convertRecordStringToDoubleTypedRange(recordString: String): DoubleTypedRange =
    DoubleTypedRange(recordString.replace(",", ":").replace(" ", ""))

  def convertFromMetersPerSecondToMilesPerHour(mps: Double): Double = mps * 2.23694
}
