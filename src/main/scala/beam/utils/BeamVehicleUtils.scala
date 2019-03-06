package beam.utils

import java.util

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.FuelType.{Biodiesel, Diesel, Electricity, Gasoline}
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, FuelType, VehicleCategory}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.agents.vehicles._
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id
import org.matsim.households.Household
import org.matsim.vehicles.EngineInformation.{FuelType => MatsimFuleType}
import org.matsim.vehicles._
import org.matsim.vehicles.{Vehicle, VehicleType, Vehicles}
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

object BeamVehicleUtils {

  def readVehiclesFile(
    filePath: String,
    vehiclesTypeMap: scala.collection.Map[Id[BeamVehicleType], BeamVehicleType]
  ): scala.collection.Map[Id[BeamVehicle], BeamVehicle] = {

    readCsvFileByLine(filePath, scala.collection.mutable.HashMap[Id[BeamVehicle], BeamVehicle]()) {
      case (line, acc) =>
        val vehicleIdString = line.get("vehicleId")
        val vehicleId = Id.create(vehicleIdString, classOf[BeamVehicle])

        val vehicleTypeIdString = line.get("vehicleTypeId")
        val vehicleType = vehiclesTypeMap(Id.create(vehicleTypeIdString, classOf[BeamVehicleType]))

        val householdIdString = line.get("householdId")

        val householdId: Option[Id[Household]] = if (householdIdString == null) {
          None
        } else {
          Some(Id.create(householdIdString, classOf[Household]))
        }

        val powerTrain = new Powertrain(vehicleType.primaryFuelConsumptionInJoulePerMeter)

        val beamVehicle = new BeamVehicle(vehicleId, powerTrain, vehicleType)
        acc += ((vehicleId, beamVehicle))
        acc
    }
  }

  def readFuelTypeFile(filePath: String): scala.collection.Map[FuelType, Double] = {
    readCsvFileByLine(filePath, scala.collection.mutable.HashMap[FuelType, Double]()) {
      case (line, z) =>
        val fuelType = FuelType.fromString(line.get("fuelTypeId"))
        val priceInDollarsPerMJoule = line.get("priceInDollarsPerMJoule").toDouble
        z += ((fuelType, priceInDollarsPerMJoule))
    }
  }

  def readBeamVehicleTypeFile(
    filePath: String,
    fuelTypePrices: scala.collection.Map[FuelType, Double]
  ): Map[Id[BeamVehicleType], BeamVehicleType] = {
    readCsvFileByLine(filePath, scala.collection.mutable.HashMap[Id[BeamVehicleType], BeamVehicleType]()) {
      case (line: util.Map[String, String], z) =>
        val vIdString = line.get("vehicleTypeId")
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
        val secondaryFuelType = secondaryFuelTypeId.map(FuelType.fromString(_))
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
          sampleProbabilityWithinCategory
        )
        z += ((vehicleTypeId, bvt))
    }.toMap
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

  def beamFuelTypeToMatsimEngineInfo(beamVehicleType: BeamVehicleType): EngineInformationImpl = {
    val fuelConsumptionInJoulePerMeter = beamVehicleType.primaryFuelConsumptionInJoulePerMeter
    beamVehicleType.primaryFuelType match {
      case Biodiesel =>
        new EngineInformationImpl(
          MatsimFuleType.biodiesel,
          fuelConsumptionInJoulePerMeter * 1 / BIODIESEL_JOULE_PER_LITER
        )
      case Diesel =>
        new EngineInformationImpl(MatsimFuleType.diesel, fuelConsumptionInJoulePerMeter * 1 / DIESEL_JOULE_PER_LITER)
      case Gasoline =>
        new EngineInformationImpl(
          MatsimFuleType.gasoline,
          fuelConsumptionInJoulePerMeter * 1 / GASOLINE_JOULE_PER_LITER
        )
      case Electricity =>
        new EngineInformationImpl(
          MatsimFuleType.electricity,
          fuelConsumptionInJoulePerMeter * 1 / ELECTRICITY_JOULE_PER_LITER
        )
      case _ =>
        new EngineInformationImpl(
          MatsimFuleType.gasoline,
          fuelConsumptionInJoulePerMeter * 1 / GASOLINE_JOULE_PER_LITER
        )
    }
  }

  // From https://www.extension.iastate.edu/agdm/wholefarm/pdf/c6-87.pdf
  val GASOLINE_JOULE_PER_LITER = 34.8E6
  val DIESEL_JOULE_PER_LITER = 38.7E6
  val BIODIESEL_JOULE_PER_LITER = 35.2E6
  val ELECTRICITY_JOULE_PER_LITER = 1

  def beamVehicleTypeToMatsimVehicleType(beamVehicleType: BeamVehicleType): VehicleType = {
    val matsimVehicleType = VehicleUtils.getFactory.createVehicleType(
      Id.create(beamVehicleType.vehicleCategory.toString, classOf[VehicleType])
    )

    val vehicleCapacity = new VehicleCapacityImpl()
    vehicleCapacity.setSeats(beamVehicleType.seatingCapacity)
    vehicleCapacity.setStandingRoom(beamVehicleType.standingRoomCapacity)
    matsimVehicleType.setCapacity(vehicleCapacity)

    val engineInformation = beamFuelTypeToMatsimEngineInfo(beamVehicleType)
    matsimVehicleType.setEngineInformation(engineInformation)

    matsimVehicleType.setLength(beamVehicleType.lengthInMeter)
    matsimVehicleType.setPcuEquivalents(beamVehicleType.passengerCarUnit)

    matsimVehicleType.setMaximumVelocity(beamVehicleType.maxVelocity.getOrElse(0.0))
    matsimVehicleType
  }

}
