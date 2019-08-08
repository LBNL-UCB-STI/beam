package beam.utils.beamToVia.appsForVisualizations

import beam.utils.beamToVia.{EventsProcessor, HashSetReader, Writer}
import beam.utils.beamToVia.beamEvent.BeamPathTraversal
import beam.utils.beamToVia.beamEventsFilter.{
  MutablePopulationFilter,
  MutableSamplingFilter,
  MutableVehiclesFilter,
  PopulationSample,
  VehicleSample
}
import beam.utils.beamToVia.viaEvent.ViaEvent

object visualization_33_34 extends App {
  val dirPath = "D:/Work/BEAM/visualizations/"

  //val beamEventsFilePath = dirPath + "v33.it30.events.third.csv"
  //val vehiclesInCircleFilePath = dirPath + "v33.it30.events.third.csv.in_SF.vehicles.txt"

  val beamEventsFilePath = dirPath + "v34.it0.events.third.csv"
  val vehiclesInCircleFilePath = dirPath + "v34.it0.events.third.csv.in_SF.vehicles.txt"

  val sampleSize = 0.3

  val viaOutputBaseFilePath = beamEventsFilePath + ".popSize" + sampleSize
  val viaEventsFile = viaOutputBaseFilePath + ".via.xml"
  val viaIdsFile = viaOutputBaseFilePath + ".ids.txt"

  val vehiclesInCircle = HashSetReader.fromFile(vehiclesInCircleFilePath)

  object Selector extends MutableVehiclesFilter.SelectNewVehicle {
    override def select(vehicleMode: String, vehicleType: String, vehicleId: String): Boolean = {
      if (!vehiclesInCircle.contains(vehicleId)) false
      else {
        vehicleMode match {
          case "CAR" | "BUS" => fitIn(sampleSize)
          case _             => false
        }
      }
    }
  }

  def vehicleType(pte: BeamPathTraversal): String = {
    if (pte.vehicleId.contains("rideHail")) pte.mode + "_RH_P%03d".format(pte.numberOfPassengers)
    else pte.mode + "_P%03d".format(pte.numberOfPassengers)
  }

  def vehicleId(pte: BeamPathTraversal): String =
    vehicleType(pte) + "__" + pte.vehicleId

  val (vehiclesEvents, _) = EventsProcessor.readWithFilter(beamEventsFilePath, MutableVehiclesFilter(Selector))
  val (events, typeToId) = EventsProcessor.transformPathTraversals(vehiclesEvents, vehicleId, vehicleType)

  Writer.writeViaEventsQueue[ViaEvent](events, _.toXml.toString, viaEventsFile)
  Writer.writeViaIdFile(typeToId, viaIdsFile)
}
