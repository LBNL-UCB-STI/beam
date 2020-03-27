package beam.utils.beam_to_matsim.visualization.via

import beam.utils.beam_to_matsim.events.BeamPathTraversal
import beam.utils.beam_to_matsim.events_filter.{MutableSamplingFilter, MutableVehiclesFilter, VehicleSample}
import beam.utils.beam_to_matsim.io.{HashSetReader, Reader, Writer}
import beam.utils.beam_to_matsim.via_event.ViaEvent

import scala.collection.mutable

object visualization_11 extends App {

  val beamEventsFilePath = "D:/Work/BEAM/visualizations/v2.it20.events.bridge_cap_5000.third.csv"
  val vehiclesInCircleFilePath = "D:/Work/BEAM/visualizations/v2.it20.events.bridge_cap_5000.half_in_SF.vehicles.txt"

  val idPrefix = ""
  val sampleSize = 1

  val viaOutputBaseFilePath = "D:/Work/BEAM/visualizations/v11.it20.events.bridge_cap_5000.third.popSize" + sampleSize
  val viaEventsFile = viaOutputBaseFilePath + ".via.xml"
  val viaIdsFile = viaOutputBaseFilePath + ".ids.txt"

  // vehicle1
  val necessaryVehicles = mutable.HashSet("rideHailVehicle-1860433")
  val vehiclesInCircle = HashSetReader.fromFile(vehiclesInCircleFilePath)

  val filter: MutableSamplingFilter = MutableVehiclesFilter.withListOfIncludeAndNecessary(
    vehiclesInCircle,
    necessaryVehicles,
    Seq(
      VehicleSample("RH_PHEV-Car_L1", sampleSize),
      VehicleSample("RH_HEV-Car_L1", sampleSize),
      VehicleSample("RH_Conventional-Truck_L1", sampleSize),
      VehicleSample("RH_BEV-Car_L1", sampleSize),
      VehicleSample("PHEV-Car_L1", sampleSize),
      VehicleSample("HEV-Car_L1", sampleSize),
      VehicleSample("Conventional-Truck_L1", sampleSize),
      VehicleSample("Conventional-Car_L1", sampleSize),
      VehicleSample("CAR", sampleSize),
      VehicleSample("BUS-WH", sampleSize),
      VehicleSample("BUS-WC", sampleSize),
      VehicleSample("BUS-VTA", sampleSize),
      VehicleSample("BUS-VN", sampleSize),
      VehicleSample("BUS-VC", sampleSize),
      VehicleSample("BUS-SR", sampleSize),
      VehicleSample("BUS-RV", sampleSize),
      VehicleSample("BUS-PE", sampleSize),
      VehicleSample("BUS-MA", sampleSize),
      VehicleSample("BUS-GG", sampleSize),
      VehicleSample("BUS-DEFAULT", sampleSize),
      VehicleSample("BUS-DE", sampleSize),
      VehicleSample("BUS-CT", sampleSize),
      VehicleSample("BUS-CM", sampleSize),
      VehicleSample("BUS-CC", sampleSize),
      VehicleSample("BUS-AC", sampleSize),
      VehicleSample("BEV-Car_L1", sampleSize)
    ),
    0
  )

  def vehicleType(pte: BeamPathTraversal): String =
    pte.mode + "_" + pte.vehicleType + "_P%03d".format(pte.numberOfPassengers)

  def vehicleId(pte: BeamPathTraversal): String =
    idPrefix + vehicleType(pte) + "__" + pte.vehicleId

  val (vehiclesEvents, _) = Reader.readWithFilter(beamEventsFilePath, filter)
  val (events, typeToId) = Reader.transformPathTraversals(vehiclesEvents, vehicleId, vehicleType)

  Writer.writeViaEventsQueue[ViaEvent](events, _.toXml.toString, viaEventsFile)
  Writer.writeViaIdFile(typeToId, viaIdsFile)
}
