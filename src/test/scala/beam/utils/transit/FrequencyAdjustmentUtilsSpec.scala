package beam.utils.transit

import java.nio.file.{Files, Paths}

import beam.utils.FileUtils
import com.conveyal.gtfs.GTFSFeed
import com.conveyal.r5.transit.TransitLayer
import org.scalatest.{Matchers, WordSpecLike}

class FrequencyAdjustmentUtilsSpec extends WordSpecLike with Matchers {

  "FrequencyAdjustmentUtils" should {
    "generate FrequencyAdjustment.csv from transitLayer with frequencies" in {
      val transitLayer = new TransitLayer
      loadGtfsIntoToTransitLayer("r5", transitLayer)

      val frequencyAdjustmentCsvFile = getClass.getResource("/r5").getPath + "/FrequencyAdjustment.csv"
      FrequencyAdjustmentUtils.generateFrequencyAdjustmentCsvFile(transitLayer, frequencyAdjustmentCsvFile)

      FileUtils.readAllLines(frequencyAdjustmentCsvFile) shouldBe Seq(
        "trip_id,start_time,end_time,headway_secs,exact_times",
        "bus:B1-EAST-1,06:00:00,22:00:00,300,",
        "bus:B1-WEST-1,06:00:00,22:00:00,300,",
        "train:R2-NORTH-1,06:00:00,22:00:00,600,",
        "train:R2-SOUTH-1,06:00:00,22:00:00,600,"
      )

      Files.delete(Paths.get(frequencyAdjustmentCsvFile))
    }
    "generate empty FrequencyAdjustment.csv from transitLayer without frequencies" in {
      val transitLayer = new TransitLayer
      loadGtfsIntoToTransitLayer("r5-no-freqs", transitLayer)

      val frequencyAdjustmentCsvFile = getClass.getResource("/r5-no-freqs").getPath + "/FrequencyAdjustment.csv"
      FrequencyAdjustmentUtils.generateFrequencyAdjustmentCsvFile(transitLayer, frequencyAdjustmentCsvFile)

      FileUtils.readAllLines(frequencyAdjustmentCsvFile) shouldBe Seq(
        "trip_id,start_time,end_time,headway_secs,exact_times"
      )

      Files.delete(Paths.get(frequencyAdjustmentCsvFile))
    }
  }

  def loadGtfsIntoToTransitLayer(r5Dir: String, transitLayer: TransitLayer): Unit = {
    List(
      getClass.getResource(s"/$r5Dir/bus.zip").getPath,
      getClass.getResource(s"/$r5Dir/train.zip").getPath
    ).foreach { feedFile =>
      val feed = GTFSFeed.fromFile(feedFile)
      transitLayer.loadFromGtfs(feed)
      feed.close()
    }
  }
}
