package beam.utils.csv.writers

import scala.collection.JavaConverters._
import scala.collection.mutable

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.sim.BeamServices
import beam.utils.scenario.VehicleInfo
import ScenarioCsvWriter._
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

class VehiclesCsvWriter(idVehicleToVehicleTypeIdAsStr: Map[Id[BeamVehicle], String])
    extends ScenarioCsvWriter
    with StrictLogging {

  override protected val fields: Seq[String] = Seq("vehicleId", "vehicleTypeId", "householdId")

  def vehicleType(vehicleId: Id[BeamVehicle]): String = {
    idVehicleToVehicleTypeIdAsStr.getOrElse(vehicleId, "")
  }

  override def contentIterator(scenario: Scenario): Iterator[String] = {
    val households: mutable.Map[Id[Household], Household] = scenario.getHouseholds.getHouseholds.asScala

    val allVehicles = households.values.iterator.flatMap { hh =>
      hh.getVehicleIds.asScala.map { id: Id[Vehicle] =>
        VehicleInfo(id.toString, vehicleType(id), hh.getId.toString)
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
      vehicleInfo.householdId
    ).mkString("", FieldSeparator, LineSeparator)
  }
}

object VehiclesCsvWriter {

  def apply(beamServices: BeamServices): VehiclesCsvWriter = {
    val pVehicles: Map[Id[BeamVehicle], String] = beamServices.beamScenario.privateVehicles.map {
      case (id: Id[BeamVehicle], vehicle: BeamVehicle) =>
        id -> vehicle.beamVehicleType.id.toString.trim
    }.toMap
    new VehiclesCsvWriter(pVehicles)
  }

  def apply(elements: Iterable[VehicleInfo]): VehiclesCsvWriter = {
    val pVehicles: Map[Id[BeamVehicle], String] = elements.map { vehicleInfo: VehicleInfo =>
      val id = Id.create(vehicleInfo.vehicleId, classOf[BeamVehicle])
      val vehicleTypeId = vehicleInfo.vehicleTypeId
      id -> vehicleTypeId
    }.toMap
    new VehiclesCsvWriter(pVehicles)
  }

}
