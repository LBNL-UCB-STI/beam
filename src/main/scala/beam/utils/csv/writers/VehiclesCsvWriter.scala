package beam.utils.csv.writers

import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.mutable

object VehiclesCsvWriter extends ScenarioCsvWriter with StrictLogging {
  override protected val fields: Seq[String] = Seq("vehicleId", "vehicleTypeId", "householdId")

  private case class VehicleEntry(vehicleId: Int, vehicleTypeId: String, householdId: Int) {
    override def toString: String = {
      Seq(vehicleId, vehicleTypeId, householdId).mkString("", LineSeparator, LineSeparator)
    }
  }

  override def contentIterator(scenario: Scenario): Iterator[String] = {
        val households: mutable.Map[Id[Household], Household] = scenario.getHouseholds.getHouseholds.asScala

        val vehicleIdToVehicleType: mutable.Map[Id[Vehicle], String] = scenario.getVehicles.getVehicles.asScala
          .map {
            case (id, value) => (id, value.getType.getId.toString)
          }
        val allVehicles = households.values.flatMap { hh =>
          hh.getVehicleIds.asScala.map { id: Id[Vehicle] =>
            val vehicleType = vehicleIdToVehicleType.getOrElse(id, "")
            if (vehicleType.isEmpty) {
              logger.warn(s"Could not find vehicleType of vehicleId: [$id]")
            }
            VehicleEntry(id.toString.toInt, vehicleType, hh.getId.toString.toInt).toString
          }
        }
        allVehicles.toIterator
  }

}
