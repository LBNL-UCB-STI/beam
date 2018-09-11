package beam.integration

import java.io.File

import beam.sim.BeamHelper
import beam.sim.config.BeamConfig
import com.typesafe.config.{Config, ConfigValueFactory}
import org.matsim.api.core.v01.population.{Activity, Leg}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.population.io.PopulationReader
import org.matsim.core.population.routes.NetworkRoute
import org.matsim.core.scenario.ScenarioUtils
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.collection.JavaConverters._

/**
  * Created by fdariasm on 29/08/2017
  *
  */
class EventsFileSpec
    extends FlatSpec
    with BeforeAndAfterAll
    with Matchers
    with BeamHelper
    with EventsFileHandlingCommon
    with IntegrationSpecCommon {

  private lazy val config: Config = baseConfig
    .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml,csv"))
    .withValue("beam.routing.transitOnStreetNetwork", ConfigValueFactory.fromAnyRef("true"))
    .resolve()

  lazy val beamConfig = BeamConfig(config)
  var matsimConfig: org.matsim.core.config.Config = _

  override protected def beforeAll(): Unit = {
    matsimConfig = runBeamWithConfig(config)._1
  }

  // TODO: probably test needs to be updated due to update in rideHailManager
  ignore should "contain all bus routes" in {
    val listTrips =
      getListIDsWithTag(new File("test/input/beamville/r5/bus/trips.txt"), "route_id", 2).sorted
    val listValueTagEventFile = new ReadEventsBeam()
      .getListTagsFromFile(
        getEventsFilePath(matsimConfig, "xml"),
        Some("vehicle_type", "bus"),
        "vehicle"
      )
      .groupBy(identity)
    listValueTagEventFile.size shouldBe listTrips.size
  }

  it should "contain all train routes" ignore {
    val listTrips =
      getListIDsWithTag(new File("test/input/beamville/r5/train/trips.txt"), "route_id", 2).sorted
    val listValueTagEventFile = new ReadEventsBeam()
      .getListTagsFromFile(
        getEventsFilePath(matsimConfig, "xml"),
        Some("vehicle_type", "subway"),
        "vehicle"
      )
      .groupBy(identity)
    listValueTagEventFile.size shouldBe listTrips.size
  }

  ignore should "contain the same bus trips entries" in {
    val listTrips =
      getListIDsWithTag(new File("test/input/beamville/r5/bus/trips.txt"), "route_id", 2).sorted
    val listValueTagEventFile = new ReadEventsBeam()
      .getListTagsFromFile(
        getEventsFilePath(matsimConfig, "xml"),
        Some("vehicle_type", "bus"),
        "vehicle"
      )
      .groupBy(identity)
      .keys
      .toSeq
    val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
    listTripsEventFile shouldBe listTrips
  }

  it should "contain the same train trips entries" ignore {
    val listTrips =
      getListIDsWithTag(new File("test/input/beamville/r5/train/trips.txt"), "route_id", 2).sorted
    val listValueTagEventFile = new ReadEventsBeam()
      .getListTagsFromFile(
        getEventsFilePath(matsimConfig, "xml"),
        Some("vehicle_type", "subway"),
        "vehicle"
      )
      .groupBy(identity)
      .keys
      .toSeq
    val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
    listTripsEventFile shouldBe listTrips
  }

  ignore should "contain same pathTraversal defined at stop times file for bus input file" in {
    val listTrips =
      getListIDsWithTag(new File("test/input/beamville/r5/bus/stop_times.txt"), "trip_id", 0).sorted
    val grouped = listTrips.groupBy(identity)
    val groupedWithCount = grouped.map { case (k, v) => (k, v.size - 1) }
    val listValueTagEventFile = new ReadEventsBeam().getListTagsFromFile(
      getEventsFilePath(matsimConfig, "xml"),
      Some("vehicle_type", "bus"),
      "vehicle",
      Some("PathTraversal")
    )
    val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1))
    val groupedXml = listTripsEventFile.groupBy(identity)
    val groupedXmlWithCount = groupedXml.map { case (k, v) => (k, v.size) }
    groupedXmlWithCount should contain theSameElementsAs groupedWithCount
  }

  it should "contain same pathTraversal defined at stop times file for train input file" ignore {
    val listTrips = getListIDsWithTag(
      new File("test/input/beamville/r5/train/stop_times.txt"),
      "trip_id",
      0
    ).sorted
    val grouped = listTrips.groupBy(identity)
    val groupedWithCount = grouped.map { case (k, v) => (k, v.size - 1) }
    val listValueTagEventFile = new ReadEventsBeam().getListTagsFromFile(
      getEventsFilePath(matsimConfig, "xml"),
      Some("vehicle_type", "subway"),
      "vehicle",
      Some("PathTraversal")
    )
    val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
    val groupedXml = listTripsEventFile.groupBy(identity)
    val groupedXmlWithCount = groupedXml.map { case (k, v) => (k, v.size) }
    groupedXmlWithCount should contain theSameElementsAs groupedWithCount
  }

  it should "also be available as csv file" in {
    assert(getEventsFilePath(matsimConfig, "csv").exists())
  }

  it should "also produce experienced plans which make sense" in {
    val scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    new PopulationReader(scenario).readFile(
      s"${matsimConfig.controler().getOutputDirectory}/ITERS/it.0/0.experienced_plans.xml.gz"
    )
    assert(scenario.getPopulation.getPersons.size() == 50)
    scenario.getPopulation.getPersons.values().forEach { person =>
      val experiencedPlan = person.getPlans.get(0)
      assert(experiencedPlan.getPlanElements.size() > 1)
      experiencedPlan.getPlanElements.asScala.sliding(2).foreach {
        case Seq(activity: Activity, leg: Leg) =>
          assert(activity.getEndTime == leg.getDepartureTime)
        case Seq(leg: Leg, activity: Activity) =>
          assert(leg.getDepartureTime + leg.getTravelTime == activity.getStartTime)
          if (leg.getMode == "car") {
            assert(leg.getRoute.isInstanceOf[NetworkRoute])
          }
      }
    }
  }

}
