package beam.utils.beam_to_matsim.visualization.via

import beam.utils.beam_to_matsim.events.BeamPathTraversal
import beam.utils.beam_to_matsim.events_filter.{MutableSamplingFilter, MutableVehiclesFilter, VehicleSample}
import beam.utils.beam_to_matsim.io.{HashSetReader, Reader, Writer}
import beam.utils.beam_to_matsim.via_event.ViaEvent

object visualization_6 extends App {

  val vehiclesInCircleFilePath = "D:/Work/BEAM/visualizations/v2.it20.events.bridge_cap_5000.half_in_SF.vehicles.txt"
  val vehiclesInCircle = HashSetReader.fromFile(vehiclesInCircleFilePath)

  var sample = 0.3
  process(
    "D:/Work/BEAM/visualizations/v6.0.events.csv",
    "D:/Work/BEAM/visualizations/v6.v6.0.events.popSize" + sample,
    sample
  )
  process(
    "D:/Work/BEAM/visualizations/v6.0.events.intercepts5.csv",
    "D:/Work/BEAM/visualizations/v6.0.events.intercepts5.popSize" + sample,
    sample
  )
  process(
    "D:/Work/BEAM/visualizations/v6.0.events.intercepts10.csv",
    "D:/Work/BEAM/visualizations/v6.0.events.intercepts10.popSize" + sample,
    sample
  )

  sample = 0.5
  process(
    "D:/Work/BEAM/visualizations/v6.0.events.csv",
    "D:/Work/BEAM/visualizations/v6.v6.0.events.popSize" + sample,
    sample
  )
  process(
    "D:/Work/BEAM/visualizations/v6.0.events.intercepts5.csv",
    "D:/Work/BEAM/visualizations/v6.0.events.intercepts5.popSize" + sample,
    sample
  )
  process(
    "D:/Work/BEAM/visualizations/v6.0.events.intercepts10.csv",
    "D:/Work/BEAM/visualizations/v6.0.events.intercepts10.popSize" + sample,
    sample
  )

  private def process(beamEventsFilePath: String, viaOutputBaseFilePath: String, sampleSize: Double): Unit = {
    val idPrefix = ""

    val viaEventsFile = viaOutputBaseFilePath + ".via.xml"
    val viaIdsFile = viaOutputBaseFilePath + ".ids.txt"

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
      pte.mode + "_" + pte.vehicleType + "_VC%d".format(pte.numberOfPassengers / 5)

    def vehicleId(pte: BeamPathTraversal): String =
      idPrefix + vehicleType(pte) + "__" + pte.vehicleId

    val (vehiclesEvents, _) = Reader.readWithFilter(beamEventsFilePath, filter)
    val (events, typeToId) = Reader.transformPathTraversals(vehiclesEvents, vehicleId, vehicleType)

    Writer.writeViaEventsQueue[ViaEvent](events, _.toXml.toString, viaEventsFile)
    Writer.writeViaIdFile(typeToId, viaIdsFile)
  }
}
