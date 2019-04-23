package beam.sim.vehiclesharing

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import org.matsim.api.core.v01.Id
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.{ControlerListener, IterationEndsListener}
import org.matsim.core.utils.io.IOUtils

import scala.collection.mutable

class RepositionManagerListener extends ControlerListener with IterationEndsListener {

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    println("works")
    if (eventsList.nonEmpty)
      RepositionManagerListener.writeRepositionEvents(event, eventsList)
  }

  val eventsList = mutable.ListBuffer.empty[(Int, Id[TAZ], Id[VehicleManager], Id[BeamVehicle], String, String)]

  def pickupEvent(
    time: Int,
    taz: Id[TAZ],
    vehicleManager: Id[VehicleManager],
    vehicle: Id[BeamVehicle],
    operator: String
  ): Unit = {
    storeEvent(time, taz, vehicleManager, vehicle, operator, "pickup")
  }

  def dropoffEvent(
    time: Int,
    taz: Id[TAZ],
    vehicleManager: Id[VehicleManager],
    vehicle: Id[BeamVehicle],
    operator: String
  ): Unit = {
    storeEvent(time, taz, vehicleManager, vehicle, operator, "dropoff")
  }
  private def storeEvent(
    time: Int,
    taz: Id[TAZ],
    vehicleManager: Id[VehicleManager],
    vehicle: Id[BeamVehicle],
    operator: String,
    label: String
  ): Unit = {
    eventsList.prepend((time, taz, vehicleManager, vehicle, operator, label))
  }

}

object RepositionManagerListener {
  private val repositionFileHeader = "time,taz,manager,vehicle,operator,label".split(",")

  def writeRepositionEvents(
    event: IterationEndsEvent,
    eventsList: mutable.ListBuffer[(Int, Id[TAZ], Id[VehicleManager], Id[BeamVehicle], String, String)]
  ): Unit = {
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      eventsList.head._3.toString + ".csv.gz"
    )
    val writer = IOUtils.getBufferedWriter(filePath)
    writer.write(repositionFileHeader.mkString(","))
    writer.write("\n")

    eventsList.foreach {
      case (time, taz, vehicleManager, vehicle, operator, label) =>
        writer.write(s"$time,$taz,$vehicleManager,$vehicle,$operator,$label\n")
    }
    writer.close()
  }
}
