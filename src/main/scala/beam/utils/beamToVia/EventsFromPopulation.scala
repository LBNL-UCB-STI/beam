package beam.utils.beamToVia

object EventsFromPopulation extends App {
  val beamEventsPath = "D:/Work/BEAM/Via-fs-light/2.events.xml" // 2.events.csv
  val networkPath = "D:/Work/BEAM/Via-fs-light/physsim-network.xml"

  val notADriver = (id: String) => {
    !id.contains("rideHailAgent") && !id.contains("TransitDriverAgent")
  }

  val config = RunConfig.filterPopulation(
    beamEventsPath,
    networkPath,
    Seq(PopulationSample(1, notADriver)),
    Seq(Circle(548857, 4179540, 100), Circle(552000, 4184000, 100))
  )

  TransformForPopulation.transformAndWrite(config)
}
