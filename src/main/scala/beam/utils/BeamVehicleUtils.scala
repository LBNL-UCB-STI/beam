package beam.utils

import java.util

import beam.agentsim.agents.vehicles._
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.FuelType.FuelType
import org.matsim.api.core.v01.Id
import org.matsim.households.Household
import org.matsim.vehicles.{Vehicle, VehicleType, Vehicles}
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters
import scala.collection.concurrent.TrieMap

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
  ): scala.collection.Map[Id[BeamVehicleType], BeamVehicleType] = {

    val vehicleTypes =
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
          val monetaryCostPerMeter: Double = Option(line.get("monetaryCostPerMeter")).map(_.toDouble).getOrElse(0d)
          val monetaryCostPerSecond: Double = Option(line.get("monetaryCostPerSecond")).map(_.toDouble).getOrElse(0d)
          val secondaryFuelTypeId = Option(line.get("secondaryFuelType"))
          val secondaryFuelType = secondaryFuelTypeId.map(FuelType.fromString(_))
          val secondaryFuelConsumptionInJoule =
            Option(line.get("secondaryFuelConsumptionInJoulePerMeter")).map(_.toDouble)
          val secondaryFuelCapacityInJoule = Option(line.get("secondaryFuelCapacityInJoule")).map(_.toDouble)
          val automationLevel = Option(line.get("automationLevel"))
          val maxVelocity = Option(line.get("maxVelocity")).map(_.toDouble)
          val passengerCarUnit = Option(line.get("passengerCarUnit")).map(_.toDouble).getOrElse(1d)
          val rechargeLevel2RateLimitInWatts = Option(line.get("rechargeLevel2RateLimitInWatts")).map(_.toDouble)
          val rechargeLevel3RateLimitInWatts = Option(line.get("rechargeLevel3RateLimitInWatts")).map(_.toDouble)
          val vehicleCategory = VehicleCategory.fromString(line.get("vehicleCategory"))
          val primaryVehicleEnergyFile = Option(line.get("primaryVehicleEnergyFile"))
          val secondaryVehicleEnergyFile = Option(line.get("secondaryVehicleEnergyFile"))

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
            secondaryVehicleEnergyFile
          )
          z += ((vehicleTypeId, bvt))
      }
    vehicleTypes
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

  def makeBicycle(id: Id[Vehicle]): BeamVehicle = {
    //FIXME: Every person gets a Bicycle (for now, 5/2018)

    val bvt = BeamVehicleType.defaultBicycleBeamVehicleType
    val beamVehicleId = BeamVehicle.createId(id, Some("bike"))
    val powertrain = Option(bvt.primaryFuelConsumptionInJoulePerMeter)
      .map(new Powertrain(_))
      .getOrElse(Powertrain.PowertrainFromMilesPerGallon(Powertrain.AverageMilesPerGallon))
    new BeamVehicle(
      beamVehicleId,
      powertrain,
      bvt
    )
  }

//  def makeCar(
//               matsimVehicle: Vehicle,
//               vehicleRangeInMeters: Double,
//               refuelRateLimitInWatts: Option[Double]
//             ): BeamVehicle = {
//    val engineInformation = Option(matsimVehicle.getType.getEngineInformation)
//
//    val powerTrain = engineInformation match {
//      case Some(info) =>
//        Powertrain(info)
//      case None =>
//        Powertrain.PowertrainFromMilesPerGallon(Powertrain.AverageMilesPerGallon)
//    }
//
//    val fuelCapacityInJoules = vehicleRangeInMeters * powerTrain.estimateConsumptionInJoules(1)
//
//    new BeamVehicle(
//      powerTrain,
//      matsimVehicle,
//      CarVehicle,
//      Some(fuelCapacityInJoules),
//      Some(fuelCapacityInJoules),
//      refuelRateLimitInWatts
//    )
//  }

  //TODO: Identify the vehicles by type in xml
  def makeHouseholdVehicle(
    beamVehicles: TrieMap[Id[BeamVehicle], BeamVehicle],
    id: Id[Vehicle]
  ): Either[IllegalArgumentException, BeamVehicle] = {

    if (BeamVehicleType.isBicycleVehicle(id)) {
      Right(makeBicycle(id))
    } else {
      beamVehicles
        .get(id)
        .toRight(
          new IllegalArgumentException(s"Invalid vehicle id $id")
        )
    }
  }

  def getVehicleTypeById(
    id: String,
    vehicleTypes: java.util.Map[Id[VehicleType], VehicleType]
  ): Option[VehicleType] = {
    JavaConverters
      .mapAsScalaMap(vehicleTypes)
      .filter(idAndType => idAndType._2.getId.toString.equalsIgnoreCase(id))
      .values
      .headOption
  }

  def getVehicleTypeByDescription(
    description: String,
    vehicleTypes: java.util.Map[Id[VehicleType], VehicleType]
  ): Option[VehicleType] = {
    JavaConverters
      .mapAsScalaMap(vehicleTypes)
      .filter(idAndType => idAndType._2.getDescription.equalsIgnoreCase(description))
      .values
      .headOption
  }

//  def prePopulateVehiclesByHouseHold(
//    beamServices: BeamServices
//  ): java.util.Map[Id[Household], java.util.List[Id[Vehicle]]] = {
//
//    val vehicles: java.util.Map[Id[Household], java.util.List[Id[Vehicle]]] = new util.TreeMap()
//
//    beamServices.privateVehicles.foreach {
//      case (k: Id[BeamVehicle], v: BeamVehicle) => {
//
//        var hVehicles: java.util.List[Id[Vehicle]] = vehicles.get(v.householdId.get)
//        if (hVehicles == null) {
//          hVehicles = new java.util.ArrayList[Id[Vehicle]]()
//        }
//        hVehicles.add(Id.createVehicleId(k.toString))
//        vehicles.put(v.householdId.get, hVehicles)
//
//      }
//    }
//
//    vehicles
//  }

  def getBeamVehicle(
    vehicle: Vehicle,
    household: Household,
    beamVehicleType: BeamVehicleType
  ): BeamVehicle = {

    val bvId = Id.create(vehicle.getId, classOf[BeamVehicle])
    val powerTrain = new Powertrain(beamVehicleType.primaryFuelConsumptionInJoulePerMeter)

    val beamVehicle = new BeamVehicle(bvId, powerTrain, beamVehicleType)

    beamVehicle
  }
}
