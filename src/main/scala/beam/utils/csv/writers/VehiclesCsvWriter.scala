package beam.utils.csv.writers

import beam.sim.BeamServices
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.mutable

class VehiclesCsvWriter(beamServices: BeamServices) extends ScenarioCsvWriter with StrictLogging {

  override protected val fields: Seq[String] = Seq("vehicleId", "vehicleTypeId", "householdId")

  private case class VehicleEntry(vehicleId: String, vehicleTypeId: String, householdId: String) {
    override def toString: String = {
      Seq(vehicleId, vehicleTypeId, householdId).mkString("", FieldSeparator, LineSeparator)
    }
  }

  private def vehicleType(vehicleId: Id[Vehicle]): String = {
    beamServices.beamScenario.privateVehicles
      .get(vehicleId)
      .map(
        v => v.beamVehicleType.id.toString.trim
      )
      .getOrElse("")
  }

  override def contentIterator(scenario: Scenario): Iterator[String] = {
    val households: mutable.Map[Id[Household], Household] = scenario.getHouseholds.getHouseholds.asScala

    val allVehicles = households.values.flatMap { hh =>
      hh.getVehicleIds.asScala.map { id: Id[Vehicle] =>
        VehicleEntry(id.toString, vehicleType(id), hh.getId.toString).toString
      }
    }
    allVehicles.toIterator
  }

}

object VehiclesCsvWriter {
  def apply(beamServices: BeamServices): VehiclesCsvWriter = new VehiclesCsvWriter(beamServices)
}
