package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import enumeratum.EnumEntry.LowerCamelcase
import enumeratum._
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
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

  case object TransitVehicle
      extends BeamVehicleType("transit")
      with LowerCamelcase

  case object HumanBodyVehicle
      extends BeamVehicleType("body")
      with LowerCamelcase {

    /**
      *Is the given [[Id]] a [[HumanBodyVehicle]]?
      *
      * @param id: The [[Id]] to test
      */
    def testId(id: Id[_ <: Vehicle]): Boolean = {
      id.toString.startsWith(idString)
    }

    /**
      *  Assign a new id based on the personAgent
      * @param personId: [[beam.agentsim.agents.PersonAgent]]
      * @return the id
      */
    def createId(personId: Id[Person]): Id[Vehicle] = {
      Id.create("body-" + personId.toString, classOf[Vehicle])
    }

    // TODO: Don't hardcode!!!
    // https://en.wikipedia.org/wiki/Energy_efficiency_in_transport#Walking
    def powerTrainForHumanBody(): Powertrain =
      Powertrain.PowertrainFromMilesPerGallon(360)

  }

}
