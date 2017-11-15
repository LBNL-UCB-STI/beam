package beam.agentsim.agents.vehicles

import enumeratum.EnumEntry.LowerCamelcase
import enumeratum._
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.immutable

/**
  * Enumerates the names of recognized [[BeamVehicle]]s.
  * Useful for storing canonical naming conventions.
  *
  * @author saf
  */
sealed abstract class BeamVehicleType(val idString: String) extends EnumEntry {}

case object BeamVehicleType extends Enum[BeamVehicleType] {

  val values: immutable.IndexedSeq[BeamVehicleType] = findValues

  case object CarVehicle extends BeamVehicleType("car") with LowerCamelcase

  case object TransitVehicle extends BeamVehicleType("transit") with LowerCamelcase

  case object HumanBodyVehicle extends BeamVehicleType("body") with LowerCamelcase {
    /**
      *Is the given [[Id]] a [[HumanBodyVehicle]]?
      *
      * @param id: The [[Id]] to test
      */
    def testId(id: Id[_<:Vehicle]): Boolean = {
      id.toString.equals(idString)
    }
  }

}


