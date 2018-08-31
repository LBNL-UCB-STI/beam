package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.router.Modes.BeamMode
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}

/**
  * Enumerates the names of recognized [[BeamVehicle]]s.
  * Useful for storing canonical naming conventions.
  *
  * @author saf
  */
case class BeamVehicleType(val vehicleTypeId: String,
                           seatingCapacity: Double,
                           standingRoomCapacity: Double,
                           lengthInMeter: Double,
                           primaryFuelType: FuelType,
                           primaryFuelConsumptionInJoule: Double,
                           primaryFuelCapacityInJoule: Double,
                           secondaryFuelType: FuelType,
                           secondaryFuelConsumptionInJoule: Double,
                           secondaryFuelCapacityInJoule: Double,
                           automationLevel: String,
                           maxVelocity: Double,
                           passengerCarUnit: String,
                           rechargeLevel2RateLimitInWatts: Double,
                           rechargeLevel3RateLimitInWatts: Double,
                           vehicleCategory: String){

  /**
    * Assign a new id based on the personAgent
    *
    * @param personId : The [[Id]] of the [[beam.agentsim.agents.PersonAgent]]
    * @return the id
    */
  def createId(personId: Id[Person]): Id[Vehicle] = {
    Id.create(vehicleTypeId + "-" + personId.toString, classOf[Vehicle])
  }

  /**
    * Is the given [[Id]] a [[BeamVehicle]] of type [[BeamVehicleType.vehicleTypeId]]?
    *
    * @param id : The [[Id]] to test
    */
  def isVehicleType(id: Id[_ <: Vehicle]): Boolean = {
    id.toString.startsWith(vehicleTypeId)
  }

  /**
    * Easily convert to a Matsim-based [[VehicleType]]
    */
  lazy val MatsimVehicleType: VehicleType =
    VehicleUtils.getFactory.createVehicleType(
      Id.create(this.getClass.getName, classOf[VehicleType])
    )

  /**
    * Polymorphic utility function to create the proper [[Vehicle]] for this [[BeamVehicleType]] given the id.
    *
    * Will pattern match on the type to ensure that the correct methods are internally .
    *
    * @param id The [[Id]]
    * @tparam T Can be Matsim [[Person]] or [[Vehicle]]
    * @return a properly constructed and identified Matsim [[Vehicle]].
    */
  def createMatsimVehicle[T](id: Id[T]): Vehicle = {
    id match {
      case personId: Id[Person] =>
        VehicleUtils.getFactory.createVehicle(createId(personId), MatsimVehicleType)
      case vehicleId: Id[Vehicle] =>
        VehicleUtils.getFactory.createVehicle(vehicleId, MatsimVehicleType)
    }
  }

  def toMatsimVehicleType: VehicleType = ???

}

object BeamVehicleType {
  def getBicycleType(): BeamVehicleType = ??? //TODO

  def getHumanBodyVehicle(): BeamVehicleType = {
    ??? //TODO
  }

  def getCarVehicle(): BeamVehicleType = ???

  def getTransitVehicle(): BeamVehicleType = ???

  def getRidehailVehicle(): BeamVehicleType = ???

  def createId(personId: Id[Person]): Id[Vehicle] = ???

  def createMatsimVehicle[T](id: Id[T]): Vehicle = ???

  def isHumanVehicle(beamVehicleId: Id[Vehicle]): Boolean = ???

  def isRidehailVehicle(beamVehicleId: Id[Vehicle]): Boolean = ???

  def isBicycleVehicle(beamVehicleId: Id[Vehicle]): Boolean = ???

  lazy val powerTrainForHumanBody: Powertrain = Powertrain.PowertrainFromMilesPerGallon(360)

  def getMode(beamVehicle: BeamVehicle): BeamMode = {
    ???
    //          beamVehicle.beamVehicleType match {
    //          case BicycleVehicle => BIKE
    //          case CarVehicle     => CAR
    //        }
  }

  //TODO's in BeamVehicleUtils
}

case class FuelType(fuelTypeId: String, priceInDollarsPerMJoule: Double)

//case object BeamVehicleType extends Enum[BeamVehicleType] {
//
//  val values: immutable.IndexedSeq[BeamVehicleType] = findValues
//
//  case object RideHailVehicle extends BeamVehicleType("rideHailVehicle") with LowerCamelcase
//
//  case object CarVehicle extends BeamVehicleType("car") with LowerCamelcase
//
//  case object BicycleVehicle extends BeamVehicleType("bicycle") with LowerCamelcase {
//
//    MatsimVehicleType.setMaximumVelocity(15.0 / 3.6)
//    MatsimVehicleType.setPcuEquivalents(0.25)
//    MatsimVehicleType.setDescription(idString)
//
//    // https://en.wikipedia.org/wiki/Energy_efficiency_in_transport#Bicycle
//    lazy val powerTrainForBicycle: Powertrain = Powertrain.PowertrainFromMilesPerGallon(732)
//
//  }
//
//  case object TransitVehicle extends BeamVehicleType("transit") with LowerCamelcase
//
//  case object HumanBodyVehicle extends BeamVehicleType("body") with LowerCamelcase {
//
//    // TODO: Does this need to be "Human"? Couldn't we just use the idString?
//    MatsimVehicleType.setDescription("Human")
//
//    // TODO: Don't hardcode!!!
//    // https://en.wikipedia.org/wiki/Energy_efficiency_in_transport#Walking
//    lazy val powerTrainForHumanBody: Powertrain = Powertrain.PowertrainFromMilesPerGallon(360)
//
//  }
//}
