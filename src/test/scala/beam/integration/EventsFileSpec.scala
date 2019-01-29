package beam.integration

import java.io.File

import beam.agentsim.agents.planning.BeamPlan
import beam.agentsim.events.PathTraversalEvent
import beam.analysis.plots.TollRevenueAnalysis
import beam.integration.ReadEvents._
import beam.router.Modes.BeamMode.CAR
import beam.sim.BeamHelper
import com.typesafe.config.{Config, ConfigValueFactory}
import org.matsim.api.core.v01.population.{Activity, Leg}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.population.io.PopulationReader
import org.matsim.core.population.routes.NetworkRoute
import org.matsim.core.scenario.ScenarioUtils
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.collection.JavaConverters._
import scala.io.Source

class EventsFileSpec extends FlatSpec with BeforeAndAfterAll with Matchers with BeamHelper with IntegrationSpecCommon {

  private lazy val config: Config = baseConfig
    .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml,csv"))
    .withValue("beam.routing.transitOnStreetNetwork", ConfigValueFactory.fromAnyRef("true"))
    .resolve()

  var matsimConfig: org.matsim.core.config.Config = _

  override protected def beforeAll(): Unit = {
    matsimConfig = runBeamWithConfig(config)._1
  }

  it should "contain the same bus trips entries" in {
    tripsFromEvents("BUS-DEFAULT") should contain theSameElementsAs
    tripsFromGtfs(new File("test/input/beamville/r5/bus-freq/trips.txt"))
  }

  it should "contain the same train trips entries" in {
    tripsFromEvents("SUBWAY-DEFAULT") should contain theSameElementsAs
//    tripsFromGtfs(new File("test/input/beamville/r5/train/trips.txt"))
    tripsFromGtfs(new File("test/input/beamville/r5/train-freq/trips.txt"))
  }

  private def tripsFromEvents(vehicleType: String) = {
    val trips = for {
      event <- fromFile(getEventsFilePath(matsimConfig, "xml").getAbsolutePath)
      if event.getAttributes.get("vehicleType") == vehicleType
      vehicleTag <- event.getAttributes.asScala.get("vehicle")
    } yield vehicleTag.split(":")(1).split("-").take(3).mkString("-")
    trips.toSet
  }

  private def tripsFromGtfs(file: File) = {
    val trips = for (line <- Source.fromFile(file.getPath).getLines.drop(1))
      yield line.split(",")(2)
    trips.toSet
  }

  it should "contain same pathTraversal defined at stop times file for bus input file" in {
    stopToStopLegsFromEventsByTrip("BUS-DEFAULT").keys should contain theSameElementsAs
    stopToStopLegsFromGtfsByTrip("test/input/beamville/r5/bus-freq/stop_times.txt").keys
  }

  it should "contain same pathTraversal defined at stop times file for train input file" in {
    stopToStopLegsFromEventsByTrip("SUBWAY-DEFAULT").keys should contain theSameElementsAs
    stopToStopLegsFromGtfsByTrip("test/input/beamville/r5/train-freq/stop_times.txt").keys
  }

  private def stopToStopLegsFromEventsByTrip(vehicleType: String) = {
    val pathTraversals = for {
      event <- fromFile(getEventsFilePath(matsimConfig, "xml").getAbsolutePath)
      if event.getEventType == "PathTraversal"
      if event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_TYPE) == vehicleType
    } yield event
    val eventsByTrip =
      pathTraversals.groupBy(_.getAttributes.get("vehicle").split(":")(1).split("-").take(3).mkString("-"))
    eventsByTrip.map { case (k, v) => (k, v.size) }
  }

  private def stopToStopLegsFromGtfsByTrip(stopTimesFile: String) = {
    val stopTimes = for (line <- Source.fromFile(new File(stopTimesFile).getPath).getLines.drop(1))
      yield line.split(",")
    val stopTimesByTrip = stopTimes.toList.groupBy(_(0))
    stopTimesByTrip.map { case (k, v) => (k, v.size - 1) }
  }

  it should "also be available as csv file" in {
    assert(getEventsFilePath(matsimConfig, "csv").exists())
  }

  it should "contain at least one paid toll" in {
    val tollEvents = for {
      event <- fromFile(getEventsFilePath(matsimConfig, "xml").getAbsolutePath)
      if event.getEventType == "PathTraversal"
      if event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_TOLL_PAID).toDouble != 0.0
    } yield event
    tollEvents should not be empty
  }

  it should "yield positive toll revenue according to TollRevenueAnalysis" in {
    val analysis = new TollRevenueAnalysis
    fromFile(getEventsFilePath(matsimConfig, "xml").getAbsolutePath)
      .foreach(analysis.processStats)
    val tollRevenue = analysis.getSummaryStats.get(TollRevenueAnalysis.ATTRIBUTE_TOLL_REVENUE)
    tollRevenue should not equal 0.0
  }

  it should "also produce experienced plans which make sense" in {
    val scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    new PopulationReader(scenario).readFile(
      s"${matsimConfig.controler().getOutputDirectory}/ITERS/it.0/0.experiencedPlans.xml.gz"
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
          if (leg.getMode == CAR.matsimMode) {
            assert(leg.getRoute.isInstanceOf[NetworkRoute])
          }
      }
      val beamPlan = BeamPlan(experiencedPlan)
      beamPlan.tours.foreach { tour =>
        if (tour.trips.size > 1) {
          if (tour.trips.head.leg.get.getMode == "car") {
            assert(
              tour.trips.last.leg.get.getMode == "car",
              "If I leave home by car, I must get home by car: " + person.getId
            )
          }
        }
      }
    }
  }

}
