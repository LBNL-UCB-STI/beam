package beam.utils.beamToVia.apps

import beam.utils.beamToVia.{EventsProcessor, Writer}
import beam.utils.beamToVia.beamEvent.BeamPathTraversal
import beam.utils.beamToVia.beamEventsFilter.{MutableSamplingFilter, MutableVehiclesFilter}
import beam.utils.beamToVia.viaEvent.ViaEvent

object eventsByVehicleMode extends App {
  val beamEventsFilePath = "D:/Work/BEAM/history/visualizations/v35.it3.events.csv"
  val outputFile = "D:/Work/BEAM/_tmp/output.via.xml"
  val selectedVehiclesModes = Seq[String]("car", "bus")

  val filter: MutableSamplingFilter = MutableVehiclesFilter.withListOfVehicleModes(selectedVehiclesModes)

  def vehicleType(pte: BeamPathTraversal): String = pte.mode + "_" + pte.vehicleType
  def vehicleId(pte: BeamPathTraversal): String = vehicleType(pte) + "__" + pte.vehicleId

  val (vehiclesEvents, _) = EventsProcessor.readWithFilter(beamEventsFilePath, filter)
  val (events, typeToId) = EventsProcessor.transformPathTraversals(vehiclesEvents, vehicleId, vehicleType)

  Writer.writeViaEventsQueue[ViaEvent](events, _.toXml.toString, outputFile)
  Writer.writeViaIdFile(typeToId, outputFile + ".ids.txt")
}
