package beam.utils.beamToVia

object EventsFromPathTraversal extends App {
  //val sourcePath = "D:/Work/BEAM/Via-fs-light/oddBus.xml"
  //val sourcePath = "D:/Work/BEAM/Via-fs-light/2.events.xml"
  //val sourcePath = "D:/Work/BEAM/Via-beamville/0.events.xml"
  val sourcePath = "D:/Work/BEAM/Via-fs-light/2.events.csv"

  val config = RunConfig.filterVehicles(
    sourcePath,
    Seq(
      VehicleSample("BUS-DEFAULT", 0.01),
      VehicleSample("BODY-TYPE-DEFAULT", 0.1),
      VehicleSample("CABLE_CAR-DEFAULT", 0.1),
      VehicleSample("TRAM-DEFAULT", 0.1),
      VehicleSample("Car", 0.1),
      VehicleSample("SUBWAY-DEFAULT", 0.1)
    )
  )
  TransformAllPathTraversal.transformAndWrite(config)
}
