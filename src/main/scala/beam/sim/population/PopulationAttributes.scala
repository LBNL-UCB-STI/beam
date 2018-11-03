package beam.sim.population

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.router.Modes.BeamMode
import org.matsim.api.core.v01.Id
import org.matsim.households.{Household, IncomeImpl}
import org.matsim.households.Income.IncomePeriod
import scala.collection.JavaConverters._

sealed trait PopulationAttributes


  case class AttributesOfIndividual(householdAttributes: HouseholdAttributes,
                                     householdId: Id[Household],
                                     modalityStyle: Option[String],
                                     isMale: Boolean,
                                     availableModes: Seq[BeamMode],
                                     valueOfTime: Double,
                                     age: Option[Int],
                                     income: Option[Double]
                                   ) extends PopulationAttributes
  {
    lazy val hasModalityStyle: Boolean = modalityStyle.nonEmpty
  }



case class HouseholdAttributes(
                                householdIncome: Double,
                                householdSize: Int,
                                numCars: Int,
                                numBikes: Int
                              ) extends PopulationAttributes
object HouseholdAttributes {

  def apply(household: Household, vehicles: Map[Id[BeamVehicle], BeamVehicle]): HouseholdAttributes = {
    new HouseholdAttributes(
      Option(household.getIncome)
        .getOrElse(new IncomeImpl(0, IncomePeriod.year))
        .getIncome,
      household.getMemberIds.size(),
      household.getVehicleIds.asScala
        .map(id => vehicles(id))
        .count(_.beamVehicleType.vehicleTypeId.toLowerCase.contains("car")),
      household.getVehicleIds.asScala
        .map(id => vehicles(id))
        .count(_.beamVehicleType.vehicleTypeId.toLowerCase.contains("bike"))
    )
  }
}
