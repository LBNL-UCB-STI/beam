package beam.utils.beamToVia.appsForVisualizations

import beam.utils.beamToVia.beamEvent.BeamPathTraversal
import beam.utils.beamToVia.beamEventsFilter.{MutableSamplingFilter, MutableVehiclesFilter, VehicleSample}
import beam.utils.beamToVia.viaEvent.ViaEvent
import beam.utils.beamToVia.{EventsProcessor, HashSetReader, Writer}

import scala.collection.mutable

object BeamEventsToPhysSim extends App {
  val beamEventsFilePath = "D:/Work/beam/September2019/Runs/15.events.csv"

  val viaOutputBaseFilePath = "D:/Work/beam/September2019/Runs/"
  val viaEventsFile = viaOutputBaseFilePath + ".via.xml"
  val viaIdsFile = viaOutputBaseFilePath + ".ids.txt"
  val vehiclesInCircleFilePath = "D:/Work/beam/September2019/Runs/15.events.csv.in_SF.vehicles.txt"
  val idPrefix = ""

  // vehicle1
  val necessaryVehicles = mutable.HashSet.empty[String]
  val vehiclesInCircle = HashSetReader.fromFile(vehiclesInCircleFilePath)

  val sampleSize = 1
  val filter: MutableSamplingFilter = MutableVehiclesFilter.withListOfIncludeAndNecessary(
    vehiclesInCircle,
    necessaryVehicles,
    Seq(
      VehicleSample("BUS-RV", sampleSize),
      VehicleSample("BUS-CC", sampleSize),
      VehicleSample("BUS-MA", sampleSize),
      VehicleSample("BUS-GG", sampleSize),
      VehicleSample("BUS-AY", sampleSize),
      VehicleSample("BUS-VTA", sampleSize),
      VehicleSample("BUS-SR", sampleSize),
      VehicleSample("BUS-DE", sampleSize),
      VehicleSample("BUS-PE", sampleSize),
      VehicleSample("BUS-WC", sampleSize),
      VehicleSample("BUS-VC", sampleSize),
      VehicleSample("BUS-CT", sampleSize),
      VehicleSample("BUS-DEFAULT", sampleSize),
      VehicleSample("BUS-VN", sampleSize),
      VehicleSample("BUS-AC", sampleSize),
      VehicleSample("BUS-CM", sampleSize),
      VehicleSample("BUS-WH", sampleSize),
      VehicleSample("diesel-L1-200000-to-100000000-LowTech-2019", sampleSize),
      VehicleSample("hev-L1-100000-to-200000-LowTech-2019", sampleSize),
      VehicleSample("ev-L1-10000-to-25000-LowTech-2019", sampleSize),
      VehicleSample("conv-L1-200000-to-100000000-LowTech-2019", sampleSize),
      VehicleSample("phev-L1-50000-to-75000-LowTech-2019", sampleSize),
      VehicleSample("hev-L1-50000-to-75000-LowTech-2019", sampleSize),
      VehicleSample("hev-L1-200000-to-100000000-LowTech-2019", sampleSize),
      VehicleSample("ev-L1-200000-to-100000000-LowTech-2019", sampleSize),
      VehicleSample("phev-L1-25000-to-50000-LowTech-2019", sampleSize),
      VehicleSample("ev-L1-50000-to-75000-LowTech-2019", sampleSize),
      VehicleSample("diesel-L1-75000-to-100000-LowTech-2019", sampleSize),
      VehicleSample("phev-L1-10000-to-25000-LowTech-2019", sampleSize),
      VehicleSample("diesel-L1-25000-to-50000-LowTech-2019", sampleSize),
      VehicleSample("ev-L1-25000-to-50000-LowTech-2019", sampleSize),
      VehicleSample("diesel-L1-50000-to-75000-LowTech-2019", sampleSize),
      VehicleSample("phev-L1-200000-to-100000000-LowTech-2019", sampleSize),
      VehicleSample("hev-L1-10000-to-25000-LowTech-2019", sampleSize),
      VehicleSample("hev-L1-25000-to-50000-LowTech-2019", sampleSize),
      VehicleSample("phev-L1-100000-to-200000-LowTech-2019", sampleSize),
      VehicleSample("ev-L1-100000-to-200000-LowTech-2019", sampleSize),
      VehicleSample("diesel-L1-10000-to-25000-LowTech-2019", sampleSize),
      VehicleSample("conv-L1-100000-to-200000-LowTech-2019", sampleSize),
      VehicleSample("conv-L1-25000-to-50000-LowTech-2019", sampleSize),
      VehicleSample("hev-L1-75000-to-100000-LowTech-2019", sampleSize),
      VehicleSample("conv-L1-10000-to-25000-LowTech-2019", sampleSize),
      VehicleSample("conv-L1-50000-to-75000-LowTech-2019", sampleSize),
      VehicleSample("ev-L1-75000-to-100000-LowTech-2019", sampleSize),
      VehicleSample("phev-L1-75000-to-100000-LowTech-2019", sampleSize),
      VehicleSample("diesel-L1-100000-to-200000-LowTech-2019", sampleSize),
      VehicleSample("conv-L1-75000-to-100000-LowTech-2019", sampleSize),
    ),
    0
  )

  def vehicleType(pte: BeamPathTraversal): String =
    pte.mode + "_" + pte.vehicleType + "_P%03d".format(pte.numberOfPassengers)

  def vehicleId(pte: BeamPathTraversal): String =
    idPrefix + vehicleType(pte) + "__" + pte.vehicleId

  val (vehiclesEvents, _) = EventsProcessor.readWithFilter(beamEventsFilePath, filter)
  val (events, typeToId) = EventsProcessor.transformPathTraversals(vehiclesEvents, vehicleId, vehicleType)

  Writer.writeViaEventsQueue[ViaEvent](events, _.toXml.toString, viaEventsFile)
  Writer.writeViaIdFile(typeToId, viaIdsFile)
}