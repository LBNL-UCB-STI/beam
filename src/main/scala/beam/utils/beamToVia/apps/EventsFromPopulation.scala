package beam.utils.beamToVia.apps

import beam.utils.beamToVia.{Circle, PopulationSample, RunConfig, TransformForPopulation}

import scala.collection.mutable
import scala.io.Source

object EventsFromPopulation extends App {

  val sfCircle = Circle(548966, 4179000, 5000)

  val notADriver = (id: String) => {
    !id.contains("rideHailAgent") && !id.contains("TransitDriverAgent")
  }

  val sfLight = RunConfig.filterPopulation(
    "D:/Work/BEAM/Via-fs-light/2.events.csv",
    "D:/Work/BEAM/Via-fs-light/physsim-network.xml",
    "background_",
    Seq(PopulationSample(1, notADriver)),
    Seq(Circle(548966, 4179000, 125))
  )

  val sfbay = RunConfig.filterPopulation(
    "D:/Work/BEAM/visualizations/v1.0.events.bridge_cap_5000.csv",
    "D:/Work/BEAM/visualizations/physSimNetwork.xml",
    "",
    Seq(PopulationSample(0.05, _ => true)),
    Seq(Circle(548908, 4179266, 5000))
  )

  /*
  val rh_personsFile = "D:/Work/BEAM/visualizations/v1.0.events.bridge_cap_5000.csv.RHUsersIds.txt"
  val rhIds = mutable.HashSet.empty[String]
  val source = Source fromFile rh_personsFile
  source.getLines().foreach(rhIds += _)
  source.close()

  val sfbayRHPopulation = RunConfig.filterPopulation(
    "D:/Work/BEAM/visualizations/v1.0.events.bridge_cap_5000.csv",
    "D:/Work/BEAM/visualizations/physSimNetwork.xml",
    "",
    Seq(PopulationSample(1, pId => rhIds.contains(pId))),
    Seq(Circle(548908, 4179266, 250))
  )
   */

  //val selectedPersons = mutable.HashSet("5940616") // person1
  //val selectedPersons = mutable.HashSet("5929964") // person2
  val selectedPersons = mutable.HashSet("5820634", "5617336", "6175756", "5853486", "5796101", "5604674") // person3

  val sfbayViz2 = RunConfig.filterPopulation(
    "D:/Work/BEAM/visualizations/v2.it20.events.bridge_cap_5000.half.csv",
    "D:/Work/BEAM/visualizations/physSimNetwork.xml",
    "",
    Seq(PopulationSample(1, pId => selectedPersons.contains(pId))),
    viaEventsFileSuffix = "person3"
  )

  TransformForPopulation.transformAndWrite(sfbayViz2)
}
