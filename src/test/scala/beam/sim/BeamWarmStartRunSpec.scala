package beam.sim

import beam.analysis.plots.PersonTravelTimeAnalysis
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.scalatest.{BeforeAndAfterAllConfigMap, Matchers, WordSpecLike}

import scala.io.Source

class BeamWarmStartRunSpec extends WordSpecLike with Matchers with BeamHelper with BeforeAndAfterAllConfigMap {

  "Beam WarmStart" must {
    "run sf-light scenario for two iteration with warmstart" in {

      val baseConf = ConfigFactory
        .parseString(s"""
                       |beam.agentsim.lastIteration = 1
                       |beam.warmStart.enabled = true
                       |beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds = 0
                       |beam.warmStart.path = test/input/sf-light/warmstart
                     """.stripMargin)
        .withFallback(testConfig("test/input/sf-light/sf-light.conf"))
        .resolve()

      val (_, output) = runBeamWithConfig(baseConf)
      val averageCarSpeedIt0 = BeamWarmStartRunSpec.avgCarModeFromCsv(extractFileName(output, 0))
      val averageCarSpeedIt1 = BeamWarmStartRunSpec.avgCarModeFromCsv(extractFileName(output, 1))
      averageCarSpeedIt0 should equal(5.9 +- 1.6)
      averageCarSpeedIt1 should equal(5.9 +- 1.6)

    }
  }

  private def extractFileName(outputDir: String, iterationNumber: Int): String = {
    val outputDirectoryHierarchy =
      new OutputDirectoryHierarchy(outputDir, OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles)

    outputDirectoryHierarchy.getIterationFilename(iterationNumber, PersonTravelTimeAnalysis.fileBaseName + ".csv")
  }

}

object BeamWarmStartRunSpec {

  def avgCarModeFromCsv(filePath: String): Double = {
    val carLine = FileUtils.using(Source.fromFile(filePath)) { source =>
      source.getLines().find(_.startsWith("car"))
    }
    val allHourAvg = carLine
      .getOrElse(throw new IllegalStateException("The line does not contain 'car' as TravelTimeMode"))
      .split(",")
      .tail
      .map(_.toDouble)

    val relevantTimes = allHourAvg.filterNot(_ == 0D)
    relevantTimes.sum / relevantTimes.length
  }
}
