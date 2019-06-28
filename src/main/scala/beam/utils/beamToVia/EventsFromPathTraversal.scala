package beam.utils.beamToVia

object EventsFromPathTraversal extends App {
  //val beamEventsPath = "D:/Work/BEAM/Via-fs-light/oddBus.xml"
  //val beamEventsPath = "D:/Work/BEAM/Via-fs-light/2.events.xml"
  //val beamEventsPath = "D:/Work/BEAM/Via-beamville/0.events.xml"

  val beamEventsPath = "D:/Work/BEAM/Via-fs-light/2.events.csv"
  val networkPath = "D:/Work/BEAM/Via-fs-light/physsim-network.xml"

  val config = RunConfig.filterVehicles(
    beamEventsPath,
    networkPath,
    circleFilter = Seq(Circle(548857, 4179540, 100), Circle(552000, 4184000, 100))
  )

  val config2 = RunConfig.filterVehicles(
    beamEventsPath,
    networkPath,
    Seq(VehicleSample("BUS-DEFAULT", 0.01)),
    0.05
  )

  val config3 = RunConfig.filterVehicles(
    beamEventsPath,
    networkPath,
    Seq(VehicleSample("BUS-DEFAULT", 0.5), VehicleSample("BODY-TYPE-DEFAULT", 0)),
    0.5,
    Seq(Circle(548857, 4179540, 100), Circle(552000, 4184000, 100))
  )

  TransformAllPathTraversal.transformAndWrite(config3)
}
