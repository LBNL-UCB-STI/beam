package beam.agentsim.agents.vehicles

import enumeratum.EnumEntry.LowerCamelcase
import enumeratum._

import scala.collection.immutable


/**
  * These enumerations are defined to simplify extensibility of VehicleData.
  *
  * XXXX: What does this mean? -SAF
  */
  //TODO: Consider later specifying via json
sealed abstract class EnergyEconomyAttributes extends EnumEntry

case object EnergyEconomyAttributes extends Enum[EnergyEconomyAttributes] {

  val values: immutable.IndexedSeq[EnergyEconomyAttributes] = findValues

  case object Capacity extends EnergyEconomyAttributes with LowerCamelcase

  sealed abstract class Electric extends EnumEntry

  /**
    * Attribute names related to power consumption properties of EVs
    */
  case object Electric extends Enum[Electric] {

    val values = findValues

    case object ElectricEnergyConsumptionModelClassname extends Electric with LowerCamelcase

    case object BatteryCapacityInKWh extends Electric with LowerCamelcase

    case object MaxDischargingPowerInKW extends Electric with LowerCamelcase

    case object MaxLevel2ChargingPowerInKW extends Electric with LowerCamelcase

    case object MaxLevel3ChargingPowerInKW extends Electric with LowerCamelcase

    case object TargetCoefA extends Electric with LowerCamelcase

    case object TargetCoefB extends Electric with LowerCamelcase

    case object TargetCoefC extends Electric with LowerCamelcase

  }

  /**
    * Attribute names related to gasoline fuel energy consumption
    */
  sealed abstract class Gasoline extends EnumEntry

  case object Gasoline extends Enum[Gasoline] {

    val values = findValues

    case object GasolineFuelConsumptionRateInJoulesPerMeter extends Gasoline with LowerCamelcase

    case object FuelEconomyInKwhPerMile extends Gasoline with LowerCamelcase

    case object EquivalentTestWeight extends Gasoline with LowerCamelcase

  }

}

