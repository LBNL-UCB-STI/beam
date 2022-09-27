package beam.sim

import beam.utils.TestConfigUtils.testConfig
import beam.utils.csv.GenericCsvReader
import com.typesafe.config.ConfigFactory
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.scalatest.tagobjects.Retryable
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfterAllConfigMap, Retries}

import java.io.{File, FileInputStream}
import java.util.zip.ZipInputStream

class BeamWarmStartRunSpec
    extends AnyWordSpecLike
    with Matchers
    with BeamHelper
    with BeforeAndAfterAllConfigMap
    with Retries {

  "Beam WarmStart" must {

    "prepare WarmStart data" in {
      val baseConf = ConfigFactory
        .parseString("beam.warmStart.prepareData = true")
        .withFallback(testConfig("test/input/beamville/beam.conf"))
        .resolve()
      val (_, output, _) = runBeamWithConfig(baseConf)
      val warmStartData = new File(output, "warmstart_data.zip")

      warmStartData.exists() shouldBe true

      val zipIn = new ZipInputStream(new FileInputStream(warmStartData))
      val files = Stream.continually(zipIn.getNextEntry).takeWhile(_ != null).map(_.getName).toList
      zipIn.close()

      val expectedFiles = List(
        "output_personAttributes.xml.gz",
        "population.csv.gz",
        "households.csv.gz",
        "vehicles.csv.gz",
        "ITERS/it.2/2.skimsOD_Aggregated.csv.gz",
        "ITERS/it.2/2.skimsTAZ_Aggregated.csv.gz",
        "ITERS/it.2/2.skimsTravelTimeObservedVsSimulated_Aggregated.csv.gz",
        "ITERS/it.2/2.skimsRidehail_Aggregated.csv.gz",
        "ITERS/it.2/2.skimsFreight_Aggregated.csv.gz",
        "ITERS/it.2/2.skimsParking_Aggregated.csv.gz",
        "ITERS/it.2/2.skimsTransitCrowding_Aggregated.csv.gz",
        "ITERS/it.2/2.linkstats.csv.gz",
        "ITERS/it.2/2.plans.csv.gz",
        "ITERS/it.2/2.plans.xml.gz",
        "ITERS/it.2/2.rideHailFleet.csv.gz"
      )

      files should equal(expectedFiles)
    }

    "run beamville scenario for two iterations with warmstart" in {
      val baseConf = ConfigFactory
        .parseString("beam.agentsim.lastIteration = 1")
        .withFallback(testConfig("test/input/beamville/beam-warmstart.conf"))
        .resolve()
      val (_, output, _) = runBeamWithConfig(baseConf)
      val averageCarSpeedIt0 = BeamWarmStartRunSpec.avgCarModeFromCsv(extractFileName(output, 0))
      val averageCarSpeedIt1 = BeamWarmStartRunSpec.avgCarModeFromCsv(extractFileName(output, 1))
      logger.info("average car speed per iterations: {}, {}", averageCarSpeedIt0, averageCarSpeedIt1)
      averageCarSpeedIt0 / averageCarSpeedIt1 should equal(1.0 +- 0.15)
    }

    "run beamville scenario for two iterations with warmstart with normal and fake skims" in {
      val baseConf1 = ConfigFactory
        .parseString("beam.agentsim.lastIteration = 0")
        .withFallback(testConfig("test/input/beamville/beam-warmstart.conf"))
        .resolve()
      val (_, output1, _) = runBeamWithConfig(baseConf1)
      val averageCarSpeedIt0 = BeamWarmStartRunSpec.avgCarModeFromCsv(extractFileName(output1, 0))

      val baseConf2 = ConfigFactory
        .parseString(s"""
         beam.agentsim.lastIteration = 0
         beam.routing.overrideNetworkTravelTimesUsingSkims = true
         beam.warmStart.path = "test/input/beamville/warmstart/warmstart_data_fake_skims.zip"
         """)
        .withFallback(testConfig("test/input/beamville/beam-warmstart.conf"))
        .resolve()
      val (_, output2, _) = runBeamWithConfig(baseConf2)
      val averageCarSpeedIt1 = BeamWarmStartRunSpec.avgCarModeFromCsv(extractFileName(output2, 0))
      logger.info("average car speed per iterations: {} {}", averageCarSpeedIt0, averageCarSpeedIt1)
      (averageCarSpeedIt1 / averageCarSpeedIt0) should be > 30.0
    }

    "run beamville scenario with linkStatsOnly warmstart with linkstats only file" taggedAs Retryable in {
      val baseConf = ConfigFactory
        .parseString(s"""
         beam.agentsim.lastIteration = 1
         beam.warmStart.type = "linkStatsOnly"
         beam.warmStart.path = "test/input/beamville/warmstart/warmstart_data_linkstats_only.zip"
         """)
        .withFallback(testConfig("test/input/beamville/beam-warmstart.conf"))
        .resolve()
      val (_, output, _) = runBeamWithConfig(baseConf)
      val averageCarSpeedIt0 = BeamWarmStartRunSpec.avgCarModeFromCsv(extractFileName(output, 0))
      val averageCarSpeedIt1 = BeamWarmStartRunSpec.avgCarModeFromCsv(extractFileName(output, 1))
      logger.info("average car speed per iterations: {}, {}", averageCarSpeedIt0, averageCarSpeedIt1)
      averageCarSpeedIt0 / averageCarSpeedIt1 should equal(1.0 +- 0.15)
    }

    "run beamville scenario with linkStatsOnly warmstart and full file with fake skims" in {
      val baseConf = ConfigFactory
        .parseString(s"""
         beam.agentsim.lastIteration = 1
         beam.warmStart.type = "linkStatsOnly"
         beam.routing.overrideNetworkTravelTimesUsingSkims = true
         beam.warmStart.path = "test/input/beamville/warmstart/warmstart_data_fake_skims.zip"
         """)
        .withFallback(testConfig("test/input/beamville/beam-warmstart.conf"))
        .resolve()
      val (_, output, _) = runBeamWithConfig(baseConf)
      val averageCarSpeedIt0 = BeamWarmStartRunSpec.avgCarModeFromCsv(extractFileName(output, 0))
      val averageCarSpeedIt1 = BeamWarmStartRunSpec.avgCarModeFromCsv(extractFileName(output, 1))
      logger.info("average car speed per iterations: {}, {}", averageCarSpeedIt0, averageCarSpeedIt1)
      averageCarSpeedIt0 / averageCarSpeedIt1 should equal(1.0 +- 0.15)
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
      if (travelTimes.length == 0) 0 else travelTimes.sum / travelTimes.length
    } finally {
      toClose.close()
    }
  }
}
