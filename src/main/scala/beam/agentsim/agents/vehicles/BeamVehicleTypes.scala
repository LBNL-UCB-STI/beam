package beam.agentsim.agents.vehicles

import enumeratum.EnumEntry.LowerCamelcase
import enumeratum._

import scala.collection.immutable

/**
  * Enumerates the names of recognized BeamVehicles.
  * Useful for storing canonical naming conventions.
  *
  * @author saf
  */
sealed abstract class BeamVehicleTypes(idPrefix: String) extends EnumEntry

  case object BeamVehicleTypes extends Enum[BeamVehicleTypes]{

    val values: immutable.IndexedSeq[BeamVehicleTypes] = findValues

    case object CarVehicle extends BeamVehicleTypes("car") with LowerCamelcase

    case object TransitVehicle extends BeamVehicleTypes("transit") with LowerCamelcase

    case object HumanBodyVehicle extends BeamVehicleTypes("body") with LowerCamelcase

  }


