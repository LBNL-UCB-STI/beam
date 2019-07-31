package beam.utils.beamToVia.appsForVisualizations

import beam.utils.beamToVia.{EventsProcessor, HashSetReader, Writer}
import beam.utils.beamToVia.beamEvent.BeamPathTraversal
import beam.utils.beamToVia.beamEventsFilter.{MutableSamplingFilter, MutableVehiclesFilter, VehicleSample}
import beam.utils.beamToVia.viaEvent.ViaEvent

object visualization_34 extends App {
  val beamEventsFilePath = "D:/Work/BEAM/visualizations/v34.it20.events.csv"
  val vehiclesInCircleFilePath = "D:/Work/BEAM/visualizations/v34.it20.events.in_SF.vehicles.txt"

  val idPrefix = ""
  val sampleSize = 0.3

  val viaOutputBaseFilePath = "D:/Work/BEAM/visualizations/v34.it20.events.popSize" + sampleSize
  val viaEventsFile = viaOutputBaseFilePath + ".via.xml"
  val viaIdsFile = viaOutputBaseFilePath + ".ids.txt"

  val vehiclesInCircle = HashSetReader.fromFile(vehiclesInCircleFilePath)

  val filter: MutableSamplingFilter = MutableVehiclesFilter.withListOfInclude(
    vehiclesInCircle,
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

  val (vehiclesEvents, _) = EventsProcessor.readWithFilter(beamEventsFilePath, filter)
  val (events, typeToId) = EventsProcessor.transformPathTraversals(vehiclesEvents, vehicleId, vehicleType)

  Writer.writeViaEventsQueue[ViaEvent](events, _.toXml.toString, viaEventsFile)
  Writer.writeViaIdFile(typeToId, viaIdsFile)}
