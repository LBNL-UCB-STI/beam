package beam.utils.beamToVia

object EventsFromPopulation extends App {
  //val sourcePath = "D:/Work/BEAM/Via-beamville/0.events.xml"
  //val networkPath = "D:/Work/BEAM/Via-fs-light/physsim-network.xml"

  val sourcePath = "D:/Work/BEAM/Via-fs-light/2.events.xml" // 2.events.csv

  val notADriver = (id: String) => {
    !id.contains("rideHailAgent") && !id.contains("TransitDriverAgent")
  }

  val config = RunConfig.filterPopulation(sourcePath, Seq(PopulationSample(0.1, notADriver)))
  TransformForPopulation.transformAndWrite(config)
}
