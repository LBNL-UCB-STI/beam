package beam.sim

import java.io.Closeable

import beam.utils.TestConfigUtils.testConfig
import beam.utils.csv.GenericCsvReader
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAllConfigMap, Matchers, WordSpecLike}

import scala.collection.JavaConverters._
import scala.collection.mutable

class SecondaryActivitiesSpec extends WordSpecLike with Matchers with BeamHelper  with BeforeAndAfterAllConfigMap {
  "Beam Run" should {
    "check mode for secondary activities" in {

      val baseConf = ConfigFactory
        .parseString(s"""
                        |beam.exchange.scenario.urbansim.activitySimEnabled = true
                        |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.generate_secondary_activities = false
                        |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.intercept_file_path = "test/test-resources/beam/agentsim/activities/activity-intercepts.csv"
                        |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.activity_file_path = "test/test-resources/beam/agentsim/activities/activity-params.csv"
                     """.stripMargin)
        .withFallback(testConfig("test/input/beamville/beam.conf"))
        .resolve()

      val (_, output, _) = runBeamWithConfig(baseConf)
      val modeChoice = extractFileContent(output, "modeChoice.csv")
      val modeChoiceCommute = extractFileContent(output, "modeChoice_commute.csv")
      assert(modeChoiceCommute.values.sum < modeChoice.values.sum)

    }

    "check mode for secondary activities with 0" in {

      val baseConf = ConfigFactory
        .parseString(s"""
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
      assert(modeChoiceCommute.values.sum < modeChoice.values.sum)
    }

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

  private def toActivityMap(mapper: java.util.Map[String, String]) : mutable.Map[String, Double] = {
    mapper.asScala.map{case (key, value) => key -> value.toDouble}
  }
}
