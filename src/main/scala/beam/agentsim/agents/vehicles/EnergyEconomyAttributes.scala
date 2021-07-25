package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.BeamVehicle.FuelConsumptionData
import enumeratum.EnumEntry.LowerCamelcase
import enumeratum._
import org.matsim.vehicles.EngineInformation

import scala.collection.immutable

/**
  * These enumerations are defined to simplify extensibility of VehicleData.
  */
//TODO: How do we get fuel-level consideration in here?
//TODO: Consider later specifying via json using Circe
sealed abstract class EnergyEconomyAttributes extends EnumEntry

case object EnergyEconomyAttributes extends Enum[EnergyEconomyAttributes] {

  val values: immutable.IndexedSeq[EnergyEconomyAttributes] = findValues

  sealed abstract class Electric extends EnumEntry

  /**
    * Attribute names related to gasoline fuel energy consumption
    */
  sealed abstract class Gasoline extends EnumEntry

  /**
    * @param joulesPerMeter joules per meter
    */
  class Powertrain(joulesPerMeter: Double) {

    def estimateConsumptionInJoules(distanceInMeters: Double): Double = {
      joulesPerMeter * distanceInMeters
    }

    def getRateInJoulesPerMeter: Double = joulesPerMeter

    def estimateConsumptionInJoules(fuelConsumption: IndexedSeq[FuelConsumptionData]): Double = {
      joulesPerMeter * fuelConsumption.map(_.linkLength.getOrElse(0.0)).sum
    }

  }

  case object Capacity extends EnergyEconomyAttributes with LowerCamelcase

  /**
    * Attribute names related to power consumption properties of EVs
    */
  case object Electric extends Enum[Electric] {

    val values: immutable.IndexedSeq[Electric] = findValues

    case object ElectricEnergyConsumptionModelClassname extends Electric with LowerCamelcase

    case object BatteryCapacityInKWh extends Electric with LowerCamelcase

    case object MaxDischargingPowerInKW extends Electric with LowerCamelcase

    case object MaxLevel2ChargingPowerInKW extends Electric with LowerCamelcase

    case object MaxLevel3ChargingPowerInKW extends Electric with LowerCamelcase

    case object TargetCoefA extends Electric with LowerCamelcase

    case object TargetCoefB extends Electric with LowerCamelcase

    case object TargetCoefC extends Electric with LowerCamelcase

  }

  case object Gasoline extends Enum[Gasoline] {

    val values: immutable.IndexedSeq[Gasoline] = findValues

    case object GasolineFuelConsumptionRateInJoulesPerMeter extends Gasoline with LowerCamelcase

    case object FuelEconomyInKwhPerMile extends Gasoline with LowerCamelcase

    case object EquivalentTestWeight extends Gasoline with LowerCamelcase

  }

  // TODO: don't hardcode... Couldn't these be put into the Enum for [[BeamVehicleType]]?
  object Powertrain {
    //according to EPA's annual report 2015
    val AverageMilesPerGallon = 24.8

    def apply(engineInformation: EngineInformation): Powertrain = {
      val jpm =
        litersPerMeterToJoulesPerMeter(engineInformation.getFuelType.name(), engineInformation.getGasConsumption)
      new Powertrain(jpm)
    }

    def litersPerMeterToJoulesPerMeter(fuelType: String, ltm: Double): Double = {
      fuelType match {
        case "gasoline" =>
          // convert from L/m to J/m
          ltm * 34.2e6 // 34.2 MJ/L, https://en.wikipedia.org/wiki/Energy_density
        case "diesel" =>
          // convert from L/m to J/m
          ltm * 35.8e6 // 35.8 MJ/L, https://en.wikipedia.org/wiki/Energy_density
        case "electricity" =>
          // convert from kWh/m to J/m
          ltm * 3.6e6 // 3.6 MJ/kWh
        case "biodiesel" =>
          // convert from L/m to J/m
          ltm * 34.5e6 // 35.8 MJ/L, https://en.wikipedia.org/wiki/Energy_content_of_biofuel
        case fuelName =>
          throw new RuntimeException(s"Unrecognized fuel type in engine information: $fuelName")
      }
    }

    def PowertrainFromMilesPerGallon(milesPerGallon: Double): Powertrain =
      new Powertrain(milesPerGallon / 120276367 * 1609.34) // 1609.34 m / mi; 120276367 J per gal

    def apply(joulesPerMeter: Option[Double]): Powertrain =
      joulesPerMeter
        .map(new Powertrain(_))
        .getOrElse(Powertrain.PowertrainFromMilesPerGallon(Powertrain.AverageMilesPerGallon))
  }

}
