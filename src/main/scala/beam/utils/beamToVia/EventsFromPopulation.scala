package beam.utils.beamToVia

object EventsFromPopulation extends App {
  val notADriver = (id: String) => {
    !id.contains("rideHailAgent") && !id.contains("TransitDriverAgent")
  }

  val config = RunConfig.filterPopulation(
    "D:/Work/BEAM/Via-fs-light/2.events.csv",
    "D:/Work/BEAM/Via-fs-light/physsim-network.xml",
    Seq(PopulationSample(1, notADriver)),
    Seq(Circle(548857, 4179540, 100), Circle(552000, 4184000, 100))
  )

  val followPersonConfig = RunConfig.filterPopulation(
    "D:/Work/BEAM/Via-fs-light/2.events.xml",
    "D:/Work/BEAM/Via-fs-light/physsim-network.xml",
    Seq(PopulationSample(1, id => id == "010200-2014001327621-0-7815205"))
  )

  val sfbayPopulationConfig = RunConfig.filterPopulation(
    "D:/Work/BEAM/sfbay-smart-base/30.events.csv",
    "D:/Work/BEAM/sfbay-smart-base/physSimNetwork.xml",
    Seq(PopulationSample(0.5, notADriver)),
    Seq(Circle(548908,4179266,25))
  )

  TransformForPopulation.transformAndWrite(sfbayPopulationConfig)
}
