package beam.integration

import java.nio.charset.StandardCharsets
import beam.agentsim.agents.planning.BeamPlan
import beam.agentsim.events.PathTraversalEvent
import beam.analysis.plots.TollRevenueAnalysis
import beam.router.Modes.BeamMode.{BIKE, CAR}
import beam.sim.BeamHelper
import beam.sim.config.BeamExecutionConfig
import beam.utils.EventReader._
import beam.utils.FileUtils
import com.google.inject
import com.typesafe.config.{Config, ConfigValueFactory}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Activity, Leg, Person}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.population.io.PopulationReader
import org.matsim.core.population.routes.NetworkRoute
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.households.Household
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll

import scala.collection.JavaConverters._
import scala.io.Source
import scala.util.Try

class EventsFileSpec
    extends AnyFlatSpec
    with BeforeAndAfterAll
    with Matchers
    with BeamHelper
    with IntegrationSpecCommon {

  private lazy val config: Config = baseConfig
    .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml,csv"))
    .withValue("beam.routing.transitOnStreetNetwork", ConfigValueFactory.fromAnyRef("true"))
    .withValue("beam.physsim.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml,csv"))
    .withValue("beam.physsim.writeEventsInterval", ConfigValueFactory.fromAnyRef("1"))
    .withValue(
      "beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.bike_intercept",
      ConfigValueFactory.fromAnyRef("6")
    )
    .resolve()

  private var scenario: MutableScenario = _
  private var personHouseholds: Map[Id[Person], Household] = _
  private var injector: inject.Injector = _

  override protected def beforeAll(): Unit = {
    val beamExecutionConfig: BeamExecutionConfig = setupBeamWithConfig(config)

    val (scenarioBuilt, beamScenario, plansMerged) = buildBeamServicesAndScenario(
      beamExecutionConfig.beamConfig,
      beamExecutionConfig.matsimConfig
    )
    scenario = scenarioBuilt
    injector = buildInjector(config, beamExecutionConfig.beamConfig, scenario, beamScenario)
    val services = buildBeamServices(injector, scenario)

    runBeam(services, scenario, beamScenario, scenario.getConfig.controler().getOutputDirectory, plansMerged)
    personHouseholds = scenario.getHouseholds.getHouseholds
      .values()
      .asScala
      .flatMap(h => h.getMemberIds.asScala.map(_ -> h))
      .toMap
  }

  override def afterAll(): Unit = {
    val travelDistanceStats = injector.getInstance(classOf[org.matsim.analysis.TravelDistanceStats])
    if (travelDistanceStats != null)
      travelDistanceStats.close()
  }

  private def tripsFromEvents(vehicleType: String): Set[String] = {
    val trips = for {
      event <- fromXmlFile(getEventsFilePath(scenario.getConfig, "events", "xml").getAbsolutePath)
      if event.getAttributes.get("vehicleType") == vehicleType
      vehicleTag <- event.getAttributes.asScala.get("vehicle")
    } yield vehicleTag.split(":")(1).split("-").take(3).mkString("-")
    trips.toSet
  }

  private def tripsFromGtfs(gtfsZip: String): Set[String] = {
    val maybeLines = FileUtils.getStreamFromZipFolder(gtfsZip, "trips.txt").map { stream =>
      Source.fromInputStream(stream, StandardCharsets.UTF_8.name()).getLines().drop(1).toList
    }
    require(maybeLines.nonEmpty, s"Couldn't read 'trips.txt' ${gtfsZip}")

    val trips = for (line <- maybeLines.get)
      yield line.split(",")(2)
    trips.toSet
  }

  private def stopToStopLegsFromEventsByTrip(vehicleType: String): Map[String, Int] = {
    val pathTraversals = for {
      event <- fromXmlFile(getEventsFilePath(scenario.getConfig, "events", "xml").getAbsolutePath)
      if event.getEventType == "PathTraversal"
      if event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_TYPE) == vehicleType
    } yield event
    val eventsByTrip =
      pathTraversals.groupBy(_.getAttributes.get("vehicle").split(":")(1).split("-").take(3).mkString("-"))
    eventsByTrip.map { case (k, v) => (k, v.size) }
  }

  private def stopToStopLegsFromGtfsByTrip(gtfsZip: String): Map[String, Int] = {
    val maybeLines = FileUtils.getStreamFromZipFolder(gtfsZip, "stop_times.txt").map { stream =>
      Source.fromInputStream(stream, StandardCharsets.UTF_8.name()).getLines().drop(1).toList
    }
    require(maybeLines.nonEmpty, s"Couldn't read 'stop_times.txt' ${gtfsZip}")

    val stopTimes = for (line <- maybeLines.get)
      yield line.split(",")
    val stopTimesByTrip = stopTimes.groupBy(_(0))
    stopTimesByTrip.map { case (k, v) => (k, v.size - 1) }
  }

  it should "contain the same bus trips entries" in {
    tripsFromEvents("BUS-DEFAULT") should contain theSameElementsAs
    tripsFromGtfs("test/input/beamville/r5/bus.zip")
  }

  it should "contain the same train trips entries" in {
    tripsFromEvents("SUBWAY-DEFAULT") should contain theSameElementsAs
    tripsFromGtfs("test/input/beamville/r5/train.zip")
  }

  it should "contain same pathTraversal defined at stop times file for bus input file" in {
    stopToStopLegsFromEventsByTrip("BUS-DEFAULT").keys should contain theSameElementsAs
    stopToStopLegsFromGtfsByTrip("test/input/beamville/r5/bus.zip").keys
  }

  it should "contain same pathTraversal defined at stop times file for train input file" in {
    stopToStopLegsFromEventsByTrip("SUBWAY-DEFAULT").keys should contain theSameElementsAs
    stopToStopLegsFromGtfsByTrip("test/input/beamville/r5/train.zip").keys
  }

  it should "also be available as csv file" in {
    assert(getEventsFilePath(scenario.getConfig, "events", "csv").exists())
  }

  it should "contain at least one paid toll" in {
    val tollEvents = for {
      event <- fromXmlFile(getEventsFilePath(scenario.getConfig, "events", "xml").getAbsolutePath)
      if event.getEventType == "PathTraversal"
      if event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_TOLL_PAID).toDouble != 0.0
    } yield event
    tollEvents should not be empty
  }

  it should "yield positive toll revenue according to TollRevenueAnalysis" in {
    val analysis = new TollRevenueAnalysis
    fromXmlFile(getEventsFilePath(scenario.getConfig, "events", "xml").getAbsolutePath)
      .foreach(analysis.processStats)
    val tollRevenue = analysis.getSummaryStats.get(TollRevenueAnalysis.ATTRIBUTE_TOLL_REVENUE)
    tollRevenue should not equal 0.0
  }

  it should "also produce experienced plans which make sense" in {
    val experiencedScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    new PopulationReader(experiencedScenario).readFile(
      s"${scenario.getConfig.controler().getOutputDirectory}/ITERS/it.0/0.experienced_plans.xml.gz"
    )
    assert(experiencedScenario.getPopulation.getPersons.size() == 50)
    var nCarTrips = 0
    var nBikeTrips = 0
    experiencedScenario.getPopulation.getPersons.values().forEach { person =>
      val experiencedPlan = person.getPlans.get(0)
      assert(experiencedPlan.getPlanElements.size() > 1)
      experiencedPlan.getPlanElements.asScala.sliding(2).foreach {
        case Seq(activity: Activity, leg: Leg) =>
          assert(activity.getEndTime == leg.getDepartureTime)
        case Seq(leg: Leg, activity: Activity) =>
          assert(leg.getDepartureTime + leg.getTravelTime == activity.getStartTime)
          if (leg.getMode == CAR.matsimMode) {
            assert(leg.getRoute.isInstanceOf[NetworkRoute])
            nCarTrips += 1
          } else if (leg.getMode == BIKE.matsimMode) {
            assert(leg.getRoute.isInstanceOf[NetworkRoute])
            nBikeTrips += 1
          }
      }
      val beamPlan = BeamPlan(experiencedPlan)
      beamPlan.tours.foreach { tour =>
        if (tour.trips.size > 1) {
          for (mode <- List("car", "bike")) {
            if (tour.trips.head.leg.get.getMode == mode) {
              assert(
                tour.trips.last.leg.get.getMode == mode,
                s"If I leave home by $mode, I must get home by $mode: " + person.getId
              )
            }
          }
        }
      }
    }
    logger.debug("nCarTrips = {}, nBikeTrips = {}", nCarTrips, nBikeTrips)
    assert(nCarTrips != 0, "At least some people must go by car")
    assert(nBikeTrips != 0, "At least some people must go by bike")
  }

  it should "contain PhysSim events" in {
    val xmlEvents = fromXmlFile(getEventsFilePath(scenario.getConfig, "physSimEvents", "xml").getAbsolutePath)
    assert(xmlEvents.nonEmpty)
    val (csvEventsIter, toClose) =
      fromCsvFile(getEventsFilePath(scenario.getConfig, "physSimEvents", "csv").getAbsolutePath, x => true)
    try {
      assert(csvEventsIter.toArray.nonEmpty)
    } finally {
      Try(toClose.close())
    }
  }

}
