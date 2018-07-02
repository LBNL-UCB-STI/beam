package beam.agentsim.agents.vehicles

import enumeratum.EnumEntry.LowerCamelcase
import enumeratum._

import scala.collection.immutable

/**
  * These enumerations are defined to simplify extensibility of VehicleData.
  *
  */
//TODO: How do we get fuel-level consideration in here?
//TODO: Consider later specifying via json using Circe
sealed abstract class EnergyEconomyAttributes extends EnumEntry

case object EnergyEconomyAttributes extends Enum[EnergyEconomyAttributes] {

  val values: immutable.IndexedSeq[EnergyEconomyAttributes] = findValues

  case object Capacity extends EnergyEconomyAttributes with LowerCamelcase

  sealed abstract class Electric extends EnumEntry

  /**
    * Attribute names related to power consumption properties of EVs
    */
  case object Electric extends Enum[Electric] {

    val values: immutable.IndexedSeq[Electric] = findValues

    case object ElectricEnergyConsumptionModelClassname
        extends Electric
        with LowerCamelcase

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

    val values: immutable.IndexedSeq[Gasoline] = findValues

    case object GasolineFuelConsumptionRateInJoulesPerMeter
        extends Gasoline
        with LowerCamelcase

    case object FuelEconomyInKwhPerMile extends Gasoline with LowerCamelcase

    case object EquivalentTestWeight extends Gasoline with LowerCamelcase

  }

  /**
    *
    * @param joulesPerMeter joules per meter
    */
  class Powertrain(joulesPerMeter: Double) {

    def estimateConsumptionAt(trajectory: Trajectory, time: Double): Double = {
      val path = trajectory.computePath(time)
      joulesPerMeter * path
    }

    def estimateConsumptionInJoules(distanceInMeters: Double): Double = {
      joulesPerMeter * distanceInMeters
    }
  }

  // TODO: don't hardcode... Couldn't these be put into the Enum for [[BeamVehicleType]]?
  object Powertrain {
    //according to EPA’s annual report 2015
    val AverageMilesPerGallon = 24.8

    def PowertrainFromMilesPerGallon(milesPerGallon: Double): Powertrain =
      new Powertrain(milesPerGallon / 120276367 * 1609.34) // 1609.34 m / mi; 120276367 J per gal
  }

}
