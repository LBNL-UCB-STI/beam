package beam.sim

import java.util.concurrent.TimeUnit

import beam.utils.TestConfigUtils.testConfig
import beam.utils.csv.GenericCsvReader
import com.typesafe.config.ConfigFactory
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.scalatest.BeforeAndAfterAllConfigMap
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

class BeamWarmStartRunSpec extends AnyWordSpecLike with Matchers with BeamHelper with BeforeAndAfterAllConfigMap {

  "Beam WarmStart" must {
    "run sf-light scenario for two iteration with warmstart" in {

      val baseConf = ConfigFactory
        .parseString(s"""
                       |beam.agentsim.lastIteration = 1
                       |beam.warmStart.type = full
                       |beam.warmStart.path = test/input/sf-light/warmstart
                     """.stripMargin)
        .withFallback(testConfig("test/input/sf-light/sf-light.conf"))
        .resolve()

      val (_, output, _) = runBeamWithConfig(baseConf)
      val averageCarSpeedIt0 = BeamWarmStartRunSpec.avgCarModeFromCsv(extractFileName(output, 0))
      val averageCarSpeedIt1 = BeamWarmStartRunSpec.avgCarModeFromCsv(extractFileName(output, 1))
      averageCarSpeedIt0 should equal(4.0 +- 1.6)
      averageCarSpeedIt1 should equal(6.0 +- 1.6)

    }
  }

  private def extractFileName(outputDir: String, iterationNumber: Int): String = {
    val outputDirectoryHierarchy =
      new OutputDirectoryHierarchy(outputDir, OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles)

    outputDirectoryHierarchy.getIterationFilename(iterationNumber, "CarRideStats.personal.csv.gz")
  }

}

object BeamWarmStartRunSpec {

  def avgCarModeFromCsv(filePath: String): Double = {
    val (rdr, toClose) =
      GenericCsvReader.readAs[Double](filePath, mapper => mapper.get("travel_time").toDouble, _ => true)
    try {
      val travelTimes = rdr.toArray
      val avg = if (travelTimes.length == 0) 0 else travelTimes.sum / travelTimes.length
      TimeUnit.SECONDS.toMinutes(avg.toLong)
    } finally {
      toClose.close()
    }
  }
}
