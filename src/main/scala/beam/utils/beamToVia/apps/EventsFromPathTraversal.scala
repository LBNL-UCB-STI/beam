package beam.utils.beamToVia.apps

import beam.utils.beamToVia.{Circle, RunConfig, TransformAllPathTraversal, VehicleSample}

object EventsFromPathTraversal extends App {
  val beamEventsPath = "D:/Work/BEAM/Via-fs-light/2.events.csv"
  val networkPath = "D:/Work/BEAM/Via-fs-light/physsim-network.xml"

  val config = RunConfig.filterVehicles(
    beamEventsPath,
    networkPath,
    "",
    circleFilter = Seq(Circle(548857, 4179540, 100), Circle(552000, 4184000, 100))
  )

  val config2 = RunConfig.filterVehicles(
    beamEventsPath,
    networkPath,
    "",
    Seq(VehicleSample("BUS-DEFAULT", 0.01)),
    0.05
  )

  val sampleSize = 0.3

  val sfCircle = Circle(548966, 4179000, 5000)

  val sfBay_visualization1 = RunConfig.filterVehicles(
    //"D:/Work/BEAM/visualizations/v1.it20.events.bridge_cap_1250.csv",
    //"D:/Work/BEAM/visualizations/v1.it20.events.bridge_cap_2500.csv",
    "D:/Work/BEAM/visualizations/v1.it20.events.bridge_cap_5000.csv",
    "D:/Work/BEAM/visualizations/physSimNetwork.xml",
    "",
    Seq(
      VehicleSample("FERRY-HF", 0),
      VehicleSample("RAIL-DEFAULT", 0),
      VehicleSample("RAIL-AM", 0),
      VehicleSample("SUBWAY-DEFAULT", 0),
      VehicleSample("Bike", 0),
      VehicleSample("TRAM-DEFAULT", 0),
      VehicleSample("CABLE_CAR-DEFAULT", 0),
      VehicleSample("BODY-TYPE-DEFAULT", 0)
    ),
    sampleSize,
    Seq(sfCircle),
    "popSize" + sampleSize
  )

  val sfBay_visualization2 = RunConfig.filterVehicles(
    "D:/Work/BEAM/visualizations/v2.it20.events.bridge_cap_5000.half2.csv",
    "D:/Work/BEAM/visualizations/physSimNetwork.xml",
    "BGRD__",
    Seq(
      VehicleSample("FERRY-HF", 0),
      VehicleSample("RAIL-DEFAULT", 0),
      VehicleSample("RAIL-AM", 0),
      VehicleSample("SUBWAY-DEFAULT", 0),
      VehicleSample("Bike", 0),
      VehicleSample("TRAM-DEFAULT", 0),
      VehicleSample("CABLE_CAR-DEFAULT", 0),
      VehicleSample("BODY-TYPE-DEFAULT", 0)
    ),
    sampleSize,
    Seq(sfCircle),
    "popSize" + sampleSize,
    excludedVehicleIds = Seq(
      "GG:5186119-16MARCH-01-WKDY-Weekday-04",
      "GG:5186117-16MARCH-01-WKDY-Weekday-04",
      "body-5929964",
      "body-5940616",
      "SF:7875027",
      "SF:7599891",
      "4135804",
      "5737033",
      "rideHailVehicle-5629603",
      "rideHailVehicle-2220972",
      "rideHailVehicle-5648063"
    )
  )

  val sfBay_visualization3or4 = RunConfig.filterVehicles(
    "D:/Work/BEAM/visualizations/v1.it20.events.bridge_cap_5000.csv",
    "D:/Work/BEAM/visualizations/physSimNetwork.xml",
    "",
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
    0,
    Seq(sfCircle),
    "popSize" + sampleSize
  )

  val sfBay_visualization5 = RunConfig.filterVehicles(
    "D:/Work/BEAM/visualizations/v1.it20.events.bridge_cap_5000.csv",
    "D:/Work/BEAM/visualizations/physSimNetwork.xml",
    "",
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
    0,
    Seq(sfCircle),
    "popSize" + sampleSize
  )

  val sfBay_visualization6 = RunConfig.filterVehicles(
    "D:/Work/BEAM/visualizations/v6.0.events.intercepts10.csv",
    //"D:/Work/BEAM/visualizations/v6.0.events.intercepts5.csv",
    //"D:/Work/BEAM/visualizations/v6.0.events.csv",
    "D:/Work/BEAM/visualizations/physSimNetwork.xml",
    "",
    Seq(VehicleSample("BODY-TYPE-DEFAULT", 0), VehicleSample("Bike", 0)),
    sampleSize,
    Seq(sfCircle),
    "popSize" + sampleSize
  )

  val sfBay_visualization7 = RunConfig.filterVehicles(
    "D:/Work/BEAM/visualizations/v1.it20.events.bridge_cap_5000.csv",
    "D:/Work/BEAM/visualizations/physSimNetwork.xml",
    "",
    vehiclesSamplesOtherTypes = sampleSize
  )

  val sfBay_visualization8 = RunConfig.filterVehicles(
    "D:/Work/BEAM/visualizations/v1.it20.events.bridge_cap_5000.csv",
    "D:/Work/BEAM/visualizations/physSimNetwork.xml",
    "",
    Seq(
      VehicleSample("RH_PHEV-Car_L1", sampleSize),
      VehicleSample("RH_HEV-Car_L1", sampleSize),
      VehicleSample("RH_Conventional-Truck_L1", sampleSize),
      VehicleSample("RH_BEV-Car_L1", sampleSize),
      VehicleSample("PHEV-Car_L1", sampleSize),
      VehicleSample("HEV-Car_L1", sampleSize),
      VehicleSample("Conventional-Truck_L1", sampleSize),
      VehicleSample("Conventional-Car_L1", sampleSize),
    ),
    0,
    Seq(sfCircle),
    "popSize" + sampleSize
  )

  TransformAllPathTraversal.transformAndWrite(sfBay_visualization2)
}
