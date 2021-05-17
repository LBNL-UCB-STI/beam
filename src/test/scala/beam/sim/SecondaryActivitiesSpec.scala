package beam.sim

import java.io.Closeable

import beam.agentsim.agents.GenericEventsSpec
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.utils.{FileUtils, NetworkHelper}
import beam.utils.TestConfigUtils.testConfig
import beam.utils.csv.GenericCsvReader
import com.google.inject
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.events.{ActivityEndEvent, Event}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.must.Matchers

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.io.Source

class SecondaryActivitiesSpec
    extends AnyWordSpecLike
    with Matchers
    with BeamHelper
    with GenericEventsSpec
    with BeforeAndAfterAll {

  def config: com.typesafe.config.Config =
    ConfigFactory
      .parseString(s"""
                    |beam.agentsim.lastIteration = 0
                    |beam.exchange.scenario.urbansim.activitySimEnabled = true
                    |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.generate_secondary_activities = true
                    |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.intercept_file_path = "test/test-resources/beam/agentsim/activities/activity-intercepts.csv"
                    |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.activity_file_path = "test/test-resources/beam/agentsim/activities/activity-params.csv"
                     """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

  private var injector: inject.Injector = _
  private var interceptMode: Set[String] = _

  override def beforeAll(): Unit = {
    val beamConfig = BeamConfig(config)
    val beamScenario = loadScenario(beamConfig)
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)

    injector = org.matsim.core.controler.Injector.createInjector(
      matsimConfig,
      module(config, beamConfig, scenario, beamScenario)
    )

    beamServices = injector.getInstance(classOf[BeamServices])

    val popAdjustment = DefaultPopulationAdjustment(beamServices)
    popAdjustment.update(scenario)

    eventManager = injector.getInstance(classOf[EventsManager])
    networkHelper = injector.getInstance(classOf[NetworkHelper])

    val interceptFilePath = beamConfig.beam.agentsim.agents.tripBehaviors.mulitnomialLogit.intercept_file_path
    val interceptPath = Source.fromFile(interceptFilePath)
    interceptMode = interceptPath.getLines().next().split(",").drop(1).toSet
    interceptPath.close()
  }

  "Secondary Activities Run" should {

    "check newly added activities " in {

      val eventsType = new java.util.concurrent.ConcurrentLinkedQueue[String]()
      val basicEventHandler = new BasicEventHandler {
        override def handleEvent(event: Event): Unit = {
          event match {
            case event: ActivityEndEvent =>
              eventsType.add(event.getActType)
            case _ =>
          }
        }
      }
      processHandlers(List(basicEventHandler))

      assert(eventsType.asScala.toSet.intersect(interceptMode) == interceptMode)
    }

    "check secondary activity mode count is less then original run mode count" in {

      val (_, output, _) = runBeamWithConfig(config)
      val modeChoice = extractFileContent(output, "modeChoice.csv")
      val modeChoiceCommute = extractFileContent(output, "modeChoice_commute.csv")
      modeChoiceCommute.foreach {
        case (mode, value) => {
          assert(value <= modeChoice(mode))
        }
      }
    }

    "check mode for secondary activities with 0 values" in {

      val baseConf = ConfigFactory
        .parseString(s"""
                        |beam.agentsim.lastIteration = 0
                        |beam.exchange.scenario.urbansim.activitySimEnabled = true
                        |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.generate_secondary_activities = true
                        |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.intercept_file_path = "test/test-resources/beam/agentsim/activities/activity-intercepts-0.csv"
                        |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.activity_file_path = "test/test-resources/beam/agentsim/activities/activity-params.csv"
                     """.stripMargin)
        .withFallback(testConfig("test/input/beamville/beam.conf"))
        .resolve()

      val (_, output, _) = runBeamWithConfig(baseConf)
      val modeChoice = extractFileContent(output, "modeChoice.csv")
      val modeChoiceCommute = extractFileContent(output, "modeChoice_commute.csv")
      assert(modeChoice == modeChoiceCommute)
    }

    "check mode for secondary activities with high values" in {

      val baseConf = ConfigFactory
        .parseString(s"""
                        |beam.agentsim.lastIteration = 0
                        |beam.exchange.scenario.urbansim.activitySimEnabled = true
                        |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.generate_secondary_activities = true
                        |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.intercept_file_path = "test/test-resources/beam/agentsim/activities/activity-intercepts-high.csv"
                        |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.activity_file_path = "test/test-resources/beam/agentsim/activities/activity-params.csv"
                     """.stripMargin)
        .withFallback(testConfig("test/input/beamville/beam.conf"))
        .resolve()

      val (_, output, _) = runBeamWithConfig(baseConf)
      val modeChoice = extractFileContent(output, "modeChoice.csv")
      val modeChoiceCommute = extractFileContent(output, "modeChoice_commute.csv")
      modeChoiceCommute.foreach {
        case (mode, value) => {
          assert(value <= modeChoice(mode))
        }
      }
    }

  }

  override protected def afterAll(): Unit = {
    val travelDistanceStats = injector.getInstance(classOf[org.matsim.analysis.TravelDistanceStats])
    if (travelDistanceStats != null)
      travelDistanceStats.close()
    scenario = null
    eventManager = null
    beamServices = null
  }

  private def extractFileContent(outputDir: String, fileName: String): mutable.Map[String, Double] = {
    val (iter: Iterator[mutable.Map[String, Double]], toClose: Closeable) =
      GenericCsvReader.readAs[mutable.Map[String, Double]](s"$outputDir/$fileName", toActivityMap, _ => true)
    try {
      iter.next() - "iterations"
    } finally {
      toClose.close()
    }
  }

  private def toActivityMap(mapper: java.util.Map[String, String]): mutable.Map[String, Double] = {
    mapper.asScala.map { case (key, value) => key -> value.toDouble }
  }
}
