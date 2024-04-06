package beam.utils.csv.writers

import scala.collection.JavaConverters._
import scala.collection.mutable

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.sim.BeamServices
import beam.utils.scenario.VehicleInfo
import ScenarioCsvWriter._
import beam.agentsim.agents.vehicles.FuelType.Electricity
import beam.utils.{FormatUtils, OutputDataDescriptor, OutputDataDescriptorObject}
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

class VehiclesCsvWriter(idVehicleToVehicleTypeIdAndSoc: Map[Id[BeamVehicle], (String, Option[Double])])
    extends ScenarioCsvWriter
    with StrictLogging {

  override protected val fields: Seq[String] = Seq("vehicleId", "vehicleTypeId", "stateOfCharge", "householdId")

  def vehicleType(vehicleId: Id[BeamVehicle]): (String, Option[Double]) = {
    idVehicleToVehicleTypeIdAndSoc.getOrElse(vehicleId, "" -> None)
  }

  override def contentIterator(scenario: Scenario): Iterator[String] = {
    val households: mutable.Map[Id[Household], Household] = scenario.getHouseholds.getHouseholds.asScala

    val allVehicles = households.values.iterator.flatMap { hh =>
      hh.getVehicleIds.asScala.map { id: Id[Vehicle] =>
        val (vehicleTypeId, stateOfCharged) = vehicleType(id)
        VehicleInfo(id.toString, vehicleTypeId, stateOfCharged, hh.getId.toString)
      }
    }
    contentIterator(allVehicles)
  }

  override def contentIterator[A](elements: Iterator[A]): Iterator[String] = {
    elements.flatMap { item =>
      item match {
        case vehicleInfo: VehicleInfo => Some(toLine(vehicleInfo))
        case _                        => None
      }
    }
  }

  private def toLine[A](vehicleInfo: A with VehicleInfo) = {
    Seq(
      vehicleInfo.vehicleId,
      vehicleInfo.vehicleTypeId,
      vehicleInfo.initialSoc.map(FormatUtils.DECIMAL_3.format).getOrElse(""),
      vehicleInfo.householdId
    ).mkString("", FieldSeparator, LineSeparator)
  }
}

object VehiclesCsvWriter {

  def apply(beamServices: BeamServices): VehiclesCsvWriter = {
    val pVehicles: Map[Id[BeamVehicle], (String, Option[Double])] =
      beamServices.beamScenario.privateVehicles.values.map {
        case vehicle: BeamVehicle if vehicle.beamVehicleType.primaryFuelType == Electricity =>
          vehicle.id -> (vehicle.beamVehicleType.id.toString, Some(vehicle.getStateOfCharge))
        case vehicle: BeamVehicle =>
          vehicle.id -> (vehicle.beamVehicleType.id.toString, None)
      }.toMap
    new VehiclesCsvWriter(
      pVehicles
    )
  }

  def apply(elements: Iterable[VehicleInfo]): VehiclesCsvWriter = {
    val pVehicles: Map[Id[BeamVehicle], (String, Option[Double])] = elements.map { vehicleInfo: VehicleInfo =>
      val id = Id.create(vehicleInfo.vehicleId, classOf[BeamVehicle])
      val vehicleTypeId = vehicleInfo.vehicleTypeId
      id -> (vehicleTypeId, vehicleInfo.initialSoc)
    }.toMap
    new VehiclesCsvWriter(pVehicles)
  }

  def iterationOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("VehiclesCsvWriter", "final_vehicles.csv", iterationLevel = true)(
      """
        vehicleId | Ids of private vehicles that are presented in this iteration
        vehicleTypeId | Vehicle type id
        stateOfCharge | State of charge of electric vehicles at the end of the iteration
        householdId | Household id the vehicle belongs to
        """
    )

  def outputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("VehiclesCsvWriter", "vehicles.csv.gz")(
      """
        vehicleId | Ids of private vehicles that are presented in this simulation
        vehicleTypeId | Vehicle type id
        stateOfCharge | State of charge of electric vehicles at the beginning of the simulation
        householdId | Household id the vehicle belongs to
        """
    )

}
