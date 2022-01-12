package beam.utils

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.agents.vehicles._
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.sim.config.BeamConfig
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import org.matsim.api.core.v01.Id
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import java.util
import java.util.concurrent.atomic.AtomicReference
import scala.util.Random

object BeamVehicleUtils {

  def readVehiclesFile(
    filePath: String,
    vehiclesTypeMap: scala.collection.Map[Id[BeamVehicleType], BeamVehicleType],
    randomSeed: Long,
    vehicleManagerId: Id[VehicleManager]
  ): scala.collection.Map[Id[BeamVehicle], BeamVehicle] = {
    val rand: Random = new Random(randomSeed)

    readCsvFileByLine(filePath, scala.collection.mutable.HashMap[Id[BeamVehicle], BeamVehicle]()) { case (line, acc) =>
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
      acc += ((vehicleId, beamVehicle))
      acc
    }
  }

  def readFuelTypeFile(filePath: String): scala.collection.Map[FuelType, Double] = {
    readCsvFileByLine(filePath, scala.collection.mutable.HashMap[FuelType, Double]()) { case (line, z) =>
      val fuelType = FuelType.fromString(line.get("fuelTypeId"))
      val priceInDollarsPerMJoule = line.get("priceInDollarsPerMJoule").toDouble
      z += ((fuelType, priceInDollarsPerMJoule))
    }
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
        val sampleProbabilityWithinCategory =
          Option(line.get("sampleProbabilityWithinCategory")).map(_.toDouble).getOrElse(1.0)
        val sampleProbabilityString = Option(line.get("sampleProbabilityString"))
        val chargingCapability = Option(line.get("chargingCapability")).flatMap(ChargingPointType(_))
        val payloadCapacity = Option(line.get("payloadCapacityInKg")).map(_.toDouble)

        val bvt = BeamVehicleType(
          vehicleTypeId,
          seatingCapacity,
          standingRoomCapacity,
          lengthInMeter,
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
          payloadCapacity
        )
        z += ((vehicleTypeId, bvt))
    }.toMap
  }

  def readBeamVehicleTypeFile(beamConfig: BeamConfig): Map[Id[BeamVehicleType], BeamVehicleType] = {
    val vehicleTypes = readBeamVehicleTypeFile(beamConfig.beam.agentsim.agents.vehicles.vehicleTypesFilePath)
    val rideHailTypeId = beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId
    val dummySharedCarId = beamConfig.beam.agentsim.agents.vehicles.dummySharedCar.vehicleTypeId
    val defaultVehicleType = BeamVehicleType(
      id = Id.create("DefaultVehicleType", classOf[BeamVehicleType]),
      seatingCapacity = 4,
      standingRoomCapacity = 0,
      lengthInMeter = 4.5,
      primaryFuelType = FuelType.Gasoline,
      primaryFuelConsumptionInJoulePerMeter = 3655.98,
      primaryFuelCapacityInJoule = 3655980000.0,
      vehicleCategory = VehicleCategory.Car
    )

    val missingTypes = Seq(
      dummySharedCarId.createId[BeamVehicleType],
      rideHailTypeId.createId[BeamVehicleType]
    ).collect {
      case vehicleId if !vehicleTypes.contains(vehicleId) => vehicleId -> defaultVehicleType.copy(id = vehicleId)
    }
    vehicleTypes ++ missingTypes
  }

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

}
