package beam.utils.scripts

import beam.utils.FileUtils
import beam.utils.scripts.VehiclesWriterCSV._
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.internal.MatsimWriter
import org.matsim.core.utils.io.AbstractMatsimWriter
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.mutable

class VehiclesWriterCSV(
                         scenario: Scenario
                       ) extends AbstractMatsimWriter with StrictLogging
  with MatsimWriter {

  private case class VehicleEntry(vehicleId: Int, vehicleTypeId: String, householdId: Int) {
    override def toString: String = {
      Seq(vehicleId, vehicleTypeId, householdId).mkString("", ColumnSeparator, LineTerminator)
    }
  }

  override def write(filename: String): Unit = {
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
        VehicleEntry(id.toString.toInt, vehicleType, hh.getId.toString.toInt)
      }
    }
    val header = Iterator(Columns.mkString("", ColumnSeparator, LineTerminator))
    val vehiclesIterator = allVehicles.toIterator.map(_.toString)

    FileUtils.writeToFile(filename, header ++ vehiclesIterator)

  }

}


object VehiclesWriterCSV {
  private val Columns = Seq("vehicleId", "vehicleTypeId", "householdId")
  private val ColumnSeparator = ","
  private val LineTerminator = "\n"

  def apply(scenario: Scenario): VehiclesWriterCSV = {
    new VehiclesWriterCSV(scenario)
  }

}
