package beam.utils

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.FuelType.{
  Biodiesel,
  Diesel,
  Electricity,
  Food,
  FuelType,
  Gasoline,
  Hydrogen,
  NaturalGas,
  Undefined
}
import beam.agentsim.agents.vehicles._
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.sim.common.{DoubleTypedRange, Range}
import beam.sim.config.BeamConfig
import beam.utils.scenario.VehicleInfo
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import com.typesafe.scalalogging.LazyLogging
import com.univocity.parsers.common.record.Record
import com.univocity.parsers.csv.CsvParser
import org.apache.avro.generic.GenericRecord
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.io.IOUtils
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import java.util
import java.util.concurrent.atomic.AtomicReference
import scala.collection.JavaConverters._
import scala.util.Random

object BeamVehicleUtils extends LazyLogging {

  def readVehicleInfosFile(filePath: String): Iterable[VehicleInfo] = {
    if (filePath.toLowerCase.endsWith(".parquet")) {
      readParquetVehiclesFile(filePath)
    } else {
      readCsvFileByLine(filePath, Vector.empty[VehicleInfo]) { case (line, acc) =>
        acc :+ toVehicleInfo(line: java.util.Map[String, String])
      }
    }
  }

  def readVehiclesFile(
    filePath: String,
    vehiclesTypeMap: scala.collection.Map[Id[BeamVehicleType], BeamVehicleType],
    randomSeed: Long,
    vehicleManagerId: Id[VehicleManager]
  ): (Map[Id[BeamVehicle], BeamVehicle], Map[Id[BeamVehicle], Double]) = {
    val rand: Random = new Random(randomSeed)
    val vehicles = readVehicleInfosFile(filePath)

    vehicles.foldLeft((Map.empty[Id[BeamVehicle], BeamVehicle], Map.empty[Id[BeamVehicle], Double])) {
      case ((vehicleAcc, socAcc), vehicleInfo) =>
        val vehicleId = Id.create(vehicleInfo.vehicleId, classOf[BeamVehicle])
        val vehicleType = vehiclesTypeMap(Id.create(vehicleInfo.vehicleTypeId, classOf[BeamVehicleType]))

        val powerTrain = new Powertrain(vehicleType.primaryFuelConsumptionInJoulePerMeter)

        val beamVehicle =
          new BeamVehicle(
            vehicleId,
            powerTrain,
            vehicleType,
            new AtomicReference(vehicleManagerId),
            randomSeed = rand.nextInt
          )

        (
          vehicleAcc + (vehicleId -> beamVehicle),
          vehicleInfo.initialSoc.fold(socAcc)(soc => socAcc + (vehicleId -> soc))
        )
    }
  }

  private[utils] def readParquetVehiclesFile(filePath: String): Iterable[VehicleInfo] = {
    val (iter, toClose) = ParquetReader.read(filePath)
    try {
      iter.map(record => toVehicleInfo(record)).toVector
    } finally {
      toClose.close()
    }
  }

  private[utils] def toVehicleInfo(line: java.util.Map[String, String]): VehicleInfo = {
    val initialSocStr = Option(line.get("stateOfCharge")).map(_.trim).filter(_.nonEmpty)
    VehicleInfo(
      vehicleId = line.get("vehicleId"),
      vehicleTypeId = line.get("vehicleTypeId"),
      initialSoc = initialSocStr.map(_.toDouble),
      householdId = line.get("householdId")
    )
  }

  private[utils] def toVehicleInfo(record: GenericRecord): VehicleInfo = {
    VehicleInfo(
      vehicleId = getFirstIfNotNull(record, Seq("vehicleId", "vehicle_id")).toString,
      vehicleTypeId = getFirstIfNotNull(record, Seq("vehicleTypeId", "vehicle_type_id")).toString,
      initialSoc = getOptional(record, Seq("stateOfCharge", "state_of_charge")).map(asDouble),
      householdId = normalizeHouseholdId(getFirstIfNotNull(record, Seq("householdId", "household_id")))
    )
  }

  private def getFirstIfNotNull(record: GenericRecord, columns: Seq[String]): AnyRef = {
    val value = columns.iterator
      .flatMap { column =>
        Option(record.getSchema.getField(column)).map(_ => column -> record.get(column))
      }
      .collectFirst { case (_, value) if value != null => value }

    value.getOrElse {
      val availableColumns = record.getSchema.getFields.asScala.map(_.name()).mkString(", ")
      throw new IllegalArgumentException(
        s"None of the expected columns [${columns.mkString(", ")}] were found with non-null values. Available columns: $availableColumns"
      )
    }
  }

  private def getOptional(record: GenericRecord, columns: Seq[String]): Option[AnyRef] =
    columns.iterator
      .flatMap(column => Option(record.getSchema.getField(column)).flatMap(_ => Option(record.get(column))))
      .toSeq
      .headOption

  private def normalizeHouseholdId(value: AnyRef): String = value match {
    case n: java.lang.Double if n.doubleValue.isWhole => n.longValue.toString
    case n: java.lang.Float if n.floatValue.isWhole   => n.longValue.toString
    case n: java.lang.Long                            => n.toString
    case n: java.lang.Integer                         => n.toString
    case n: java.lang.Short                           => n.toString
    case n: java.lang.Byte                            => n.toString
    case other =>
      val asString = other.toString
      if (asString.matches("^-?\\d+\\.0+$")) asString.takeWhile(_ != '.')
      else asString
  }

  private def asDouble(value: AnyRef): Double = {
    value match {
      case n: java.lang.Double  => n.doubleValue()
      case n: java.lang.Float   => n.doubleValue()
      case n: java.lang.Long    => n.doubleValue()
      case n: java.lang.Integer => n.doubleValue()
      case n: java.lang.Short   => n.doubleValue()
      case n: java.lang.Byte    => n.doubleValue()
      case other                => other.toString.toDouble
    }
  }

  private def optionalNonEmpty(line: util.Map[String, String], key: String): Option[String] =
    Option(line.get(key)).map(_.trim).filter(_.nonEmpty)

  def readFuelTypeFile(filePath: String): scala.collection.Map[FuelType, Double] = {
    readCsvFileByLine(filePath, scala.collection.mutable.HashMap[FuelType, Double]()) { case (line, z) =>
      val fuelType = FuelType.fromString(line.get("fuelTypeId"))
      val priceInDollarsPerMJoule = line.get("priceInDollarsPerMJoule").toDouble
      z += ((fuelType, priceInDollarsPerMJoule))
    }
  }

  def fuelTypePricesFromConfig(
    fuelTypePricesConfig: BeamConfig.Beam.Agentsim.Agents.Vehicles.FuelTypePrices
  ): scala.collection.Map[FuelType, Double] =
    Map(
      Food        -> fuelTypePricesConfig.food,
      Gasoline    -> fuelTypePricesConfig.gasoline,
      Diesel      -> fuelTypePricesConfig.diesel,
      Electricity -> fuelTypePricesConfig.electricity,
      Biodiesel   -> fuelTypePricesConfig.biodiesel,
      Hydrogen    -> fuelTypePricesConfig.hydrogen,
      NaturalGas  -> fuelTypePricesConfig.naturalGas,
      Undefined   -> fuelTypePricesConfig.undefined
    )

  /**
    * These are fallback values. One should define the vehicle weight in the vehicleTypes.csv.
    * Column name is curbWeightInKg
    * @param vehicleCategory the vehicle category
    * @return an average curb weight of a vehicle that belongs to the provided category (in kg)
    */
  private def vehicleCategoryToWeightInKg(vehicleCategory: VehicleCategory.VehicleCategory): Double =
    vehicleCategory match {
      case VehicleCategory.Body                => 70
      case VehicleCategory.Bike                => 80
      case VehicleCategory.Car                 => 2000 // Class 1&2a (GVWR <= 8500 lbs.)
      case VehicleCategory.MediumDutyPassenger => 2500
      case VehicleCategory.Class12aVocational  => 2500 // Class 1-2a vocational
      case VehicleCategory.Class2b3Vocational   => 5000 // Class 2b-3 vocational
      case VehicleCategory.Class456Vocational =>
        9000 // Class 4-6 (GVWR 14001-26000 lbs. => 6000-15000, and average of 8000-9000 lbs curb weight)
      case VehicleCategory.Class78Vocational => 13000 // CLass 7&8 (GVWR 26001 to >33,001 lbs.)
      case VehicleCategory.Class78Tractor    => 20000 // CLass 7&8 (GVWR 26001 to >33,001 lbs.)
    }

  def readBeamVehicleTypeFile(filePath: String): Map[Id[BeamVehicleType], BeamVehicleType] = {
    readCsvFileByLine(filePath, scala.collection.mutable.HashMap[Id[BeamVehicleType], BeamVehicleType]()) {
      case (line: util.Map[String, String], z) =>
        val vehicleTypeId = Id.create(line.get("vehicleTypeId"), classOf[BeamVehicleType])
        val seatingCapacity = line.get("seatingCapacity").trim.toDouble.toInt
        val standingRoomCapacity = line.get("standingRoomCapacity").trim.toDouble.toInt
        val lengthInMeter = line.get("lengthInMeter").trim.toDouble
        val primaryFuelTypeId = line.get("primaryFuelType")
        val primaryFuelType = FuelType.fromString(primaryFuelTypeId)
        val primaryFuelConsumptionInJoulePerMeter = line.get("primaryFuelConsumptionInJoulePerMeter").trim.toDouble
        val primaryFuelCapacityInJoule = line.get("primaryFuelCapacityInJoule").trim.toDouble
        val primaryVehicleEnergyFile = optionalNonEmpty(line, "primaryVehicleEnergyFile")
        val monetaryCostPerMeter: Double = optionalNonEmpty(line, "monetaryCostPerMeter").map(_.toDouble).getOrElse(0d)
        val monetaryCostPerSecond: Double = optionalNonEmpty(line, "monetaryCostPerSecond").map(_.toDouble).getOrElse(0d)
        val secondaryFuelTypeId = optionalNonEmpty(line, "secondaryFuelType")
        val secondaryFuelType = secondaryFuelTypeId.map(FuelType.fromString)
        val secondaryFuelConsumptionInJoule =
          optionalNonEmpty(line, "secondaryFuelConsumptionInJoulePerMeter").map(_.toDouble)
        val secondaryFuelCapacityInJoule = optionalNonEmpty(line, "secondaryFuelCapacityInJoule").map(_.toDouble)
        val secondaryVehicleEnergyFile = optionalNonEmpty(line, "secondaryVehicleEnergyFile")
        val automationLevel: Int = optionalNonEmpty(line, "automationLevel").map(_.toDouble.toInt).getOrElse(1)
        val maxVelocity = optionalNonEmpty(line, "maxVelocity").map(_.toDouble)
        val passengerCarUnit = optionalNonEmpty(line, "passengerCarUnit").map(_.toDouble).getOrElse(1d)
        val rechargeLevel2RateLimitInWatts = optionalNonEmpty(line, "rechargeLevel2RateLimitInWatts").map(_.toDouble)
        val rechargeLevel3RateLimitInWatts = optionalNonEmpty(line, "rechargeLevel3RateLimitInWatts").map(_.toDouble)
        val vehicleCategory = VehicleCategory.fromString(line.get("vehicleCategory"))
        val curbWeight: Double = optionalNonEmpty(line, "curbWeightInKg")
          .map(_.toDouble)
          .getOrElse(vehicleCategoryToWeightInKg(vehicleCategory))
        val sampleProbabilityWithinCategory =
          optionalNonEmpty(line, "sampleProbabilityWithinCategory").map(_.toDouble).getOrElse(1.0)
        val sampleProbabilityString = optionalNonEmpty(line, "sampleProbabilityString")
        val chargingCapability = optionalNonEmpty(line, "chargingCapability").flatMap(ChargingPointType(_))
        val payloadCapacity = optionalNonEmpty(line, "payloadCapacityInKg").map(_.toDouble)
        val wheelchairAccessible = optionalNonEmpty(line, "wheelchairAccessible").map(_.toBoolean)
        val restrictRoadsByFreeSpeed = optionalNonEmpty(line, "restrictRoadsByFreeSpeedInMeterPerSecond").map(_.toDouble)
        val idleTimeFraction = optionalNonEmpty(line, "idleTimeFraction").map(_.toDouble)
        val emissionsRatesInGramsPerMile =
          optionalNonEmpty(line, "emissionsRatesInGramsPerMile").flatMap(
            parseEmissionsString(_, Some(vehicleTypeId.toString))
          )
        val emissionsRatesFile = optionalNonEmpty(line, "emissionsRatesFile")
        val vehicleUse =
          optionalNonEmpty(line, "vehicleUse").flatMap(VehicleUse.fromStringOptional).getOrElse {
            if (payloadCapacity.exists(_ > 0)) VehicleUse.Freight else VehicleUse.Passenger
          }

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
          idleTimeFraction,
          emissionsRatesFile,
          emissionsRatesInGramsPerMile,
          vehicleUse = vehicleUse
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
    * @param linkToGradePercentFilePath link grades percent file
    * @return
    */
  def loadLinkIdToGradeMapFromCSV(csvParser: CsvParser, linkToGradePercentFilePath: String): Map[Int, Double] = {
    import scala.collection.JavaConverters._
    val linkIdHeader = "id"
    val gradeHeader = "average_gradient_percent"
    val records: Iterable[Record] = linkToGradePercentFilePath match {
      case "" =>
        List[Record]()
      case _ =>
        csvParser.iterateRecords(IOUtils.getBufferedReader(linkToGradePercentFilePath)).asScala
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
    * Parses the emissions string and returns an EmissionsProfile.
    *
    * @param emissionsString String containing emissions data for vehicle types.
    * @param vehicleTypeId Optional vehicle type id for logging purposes.
    * @return An Option containing EmissionsProfile if parsing is successful, None otherwise.
    */
  def parseEmissionsString(
    emissionsString: String,
    vehicleTypeId: Option[String] = None
  ): Option[VehicleEmissions.EmissionsProfile] = {
    import VehicleEmissions.{Emissions, EmissionsProfile}

    import scala.util.Try

    // Regular expression pattern to match emission sources and their values.
    val sourcePattern = """(\w+)\(([^)]+)\)""".r

    // Split the input string by ";" to handle multiple sources
    val emissionsMap = emissionsString
      .split(";")
      .flatMap {
        case sourcePattern(source, emissions) =>
          // Process each emission source
          val emissionMap = emissions
            .split("""|""")
            .flatMap { emission =>
              val parts = emission.split(":").map(_.trim)
              parts.length match {
                case 2 =>
                  // Valid emission entry with a value
                  Emissions
                    .fromString(parts(0))
                    .map(emissionType => (emissionType, Try(parts(1).toDouble).getOrElse(0.0)))
                case 1 =>
                  // Emission entry with a missing value, default to 0.0
                  Emissions.fromString(parts(0)).map(emissionType => (emissionType, 0.0))
                case _ =>
                  // Log error for invalid emission entry
                  logger.error(
                    s"Failed to process emission source $source with emissions $emissions " +
                    s"from emissionsRatesInGramsPerMile for vehicle type Id ${vehicleTypeId.getOrElse("NaN")} "
                  )
                  None
              }
            }
            .toMap

          // Create Emissions object from the parsed data
          val emissionsRates = Emissions(emissionMap)

          // Return the source and its corresponding Emissions object
          EmissionsProfile.fromString(source).map(process => (process, emissionsRates))

        case _ => None
      }
      .toMap

    // Return EmissionsProfile if the map is non-empty
    if (emissionsMap.nonEmpty) Some(EmissionsProfile(emissionsMap)) else None
  }

  /**
    * Converts an EmissionsProfile into a formatted string.
    *
    * @param emissionsProfile An EmissionsProfile object.
    * @return A string representation of the emissions profile in the format [Emissions Source]([Emission Type 1]:[Double value],[Emission Type 2]:[Double value], ...).
    */
  def buildEmissionsString(emissionsProfile: VehicleEmissions.EmissionsProfile): String = {
    import VehicleEmissions.Emissions

    def formatEmission(emissionType: Emissions.EmissionType, value: Double): String =
      s"${Emissions.formatName(emissionType)}:$value"

    def formatEmissions(emissions: VehicleEmissions.Emissions): String = {
      emissions.values
        .map { case (emissionType, value) =>
          formatEmission(emissionType, value)
        }
        .mkString(", ")
    }

    emissionsProfile.values
      .map { case (source, emissions) =>
        s"${source.toString}(${formatEmissions(emissions)})"
      }
      .mkString("; ")
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
