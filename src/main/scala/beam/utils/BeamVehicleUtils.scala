package beam.utils

import java.util

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.FuelType.{Biodiesel, Diesel, Electricity, Gasoline}
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id
import org.matsim.households.Household
import org.matsim.vehicles.EngineInformation.FuelType
import org.matsim.vehicles._

import scala.collection.JavaConverters
import scala.collection.concurrent.TrieMap

object BeamVehicleUtils {

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

  def getBeamVehicle(vehicle: Vehicle, household: Household, beamVehicleType: BeamVehicleType): BeamVehicle = {

    val bvId = Id.create(vehicle.getId, classOf[BeamVehicle])
    val powerTrain = new Powertrain(beamVehicleType.primaryFuelConsumptionInJoulePerMeter)

    val beamVehicle = new BeamVehicle(bvId, powerTrain, beamVehicleType)

    beamVehicle
  }

  def beamFuelTypeToMatsimEngineInfo(beamVehicleType: BeamVehicleType): EngineInformationImpl = {
    val fuelConsumptionInJoulePerMeter = beamVehicleType.primaryFuelConsumptionInJoulePerMeter
    beamVehicleType.primaryFuelType match {
      case Biodiesel =>
        new EngineInformationImpl(FuelType.biodiesel, fuelConsumptionInJoulePerMeter * 1 / BIODIESEL_JOULE_PER_LITER)
      case Diesel => new EngineInformationImpl(FuelType.diesel, fuelConsumptionInJoulePerMeter * 1 / DIESEL_JOULE_PER_LITER)
      case Gasoline => new EngineInformationImpl(FuelType.gasoline, fuelConsumptionInJoulePerMeter * 1 / GASOLINE_JOULE_PER_LITER)
      case Electricity => new EngineInformationImpl(FuelType.electricity, fuelConsumptionInJoulePerMeter * 1 / ELECTRICITY_JOULE_PER_LITER)
      case _ => new EngineInformationImpl(FuelType.gasoline, fuelConsumptionInJoulePerMeter * 1 / GASOLINE_JOULE_PER_LITER)
    }
  }


  // From https://www.extension.iastate.edu/agdm/wholefarm/pdf/c6-87.pdf
  val GASOLINE_JOULE_PER_LITER = 34.8E6
  val DIESEL_JOULE_PER_LITER = 38.7E6
  val BIODIESEL_JOULE_PER_LITER = 35.2E6
  val ELECTRICITY_JOULE_PER_LITER = 1


  def beamVehicleTypeToMatsimVehicleType(beamVehicleType: BeamVehicleType): VehicleType = {
    val matsimVehicleType = VehicleUtils.getFactory.createVehicleType(Id.create(beamVehicleType.vehicleCategory.toString, classOf[VehicleType]))

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
