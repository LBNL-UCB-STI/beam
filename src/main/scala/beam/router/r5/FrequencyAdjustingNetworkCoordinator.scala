package beam.router.r5

import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import java.util

import beam.sim.config.BeamConfig
import beam.utils.CsvFileUtils
import com.conveyal.gtfs.GTFSFeed
import com.conveyal.gtfs.model.Trip
import com.conveyal.r5.analyst.scenario.{AddTrips, AdjustFrequency, Scenario}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

case class FrequencyAdjustingNetworkCoordinator(beamConfig: BeamConfig) extends NetworkCoordinator {

  lazy val feeds: Set[GTFSFeed] = getGTFSFeeds(beamConfig.beam.routing.r5.directory)
  var frequencyData: Set[FrequencyAdjustmentInput] = _

  def loadFrequencyData(): Set[FrequencyAdjustmentInput] =
    CsvFileUtils
      .readCsvFileByLineToList(beamConfig.beam.agentsim.scenarios.frequencyAdjustmentFile) { row =>
        FrequencyAdjustmentInput(
          row.get("trip_id"),
          row.get("start_time").toInt,
          row.get("end_time").toInt,
          row.get("headway_secs").toInt,
          row.get("exact_times").toInt
        )
      }
      .toSet

  def buildFrequencyAdjustmentScenario(adjustmentInputs: Set[FrequencyAdjustmentInput]): Scenario = {
    val scenario = new Scenario()

    adjustmentInputs
      .groupBy { adjustment =>
        s"${feeds.head.feedId}:${getTripForId(adjustment.tripId).route_id}"
      }
      .foreach {
        case (rid, adjustments) =>
          val entries = adjustments.map(adjustTripFrequency)
          val listEntries = new util.ArrayList[AddTrips.PatternTimetable](entries.asJava)

          val adjustFrequency = new AdjustFrequency
          adjustFrequency.route = rid
          adjustFrequency.entries = listEntries
          scenario.modifications.add(adjustFrequency)
      }

    scenario
  }

  def getTripForId(tripId: String): Trip = {
    feeds.map { feed =>
      feed.trips.asScala(tripId)
    }.head
  }

  def adjustTripFrequency(adjustmentInput: FrequencyAdjustmentInput): AddTrips.PatternTimetable = {
    val entry = new AddTrips.PatternTimetable
    entry.headwaySecs = adjustmentInput.headwaySecs
    entry.startTime = adjustmentInput.startTime
    entry.endTime = adjustmentInput.endTime
    entry.sunday = true
    entry.monday = entry.sunday
    entry.tuesday = entry.monday
    entry.wednesday = entry.tuesday
    entry.thursday = entry.wednesday
    entry.friday = entry.thursday
    entry.saturday = entry.friday
    entry.sourceTrip = s"${feeds.head.feedId}:${adjustmentInput.tripId}"
    entry
  }

  case class FrequencyAdjustmentInput(
    tripId: String,
    startTime: Int,
    endTime: Int,
    headwaySecs: Int,
    exactTimes: Int = 0
  )

  def getAllGTFSFiles(pathToR5Dir: String): ArrayBuffer[Path] = {
    val files = ArrayBuffer.empty[Path]
    val r5Path = Paths.get(s"$pathToR5Dir")
    Files.walkFileTree(
      r5Path,
      new SimpleFileVisitor[Path] {
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          if (file.getFileName.toString.endsWith(".zip")) {
            files += file
          }
          FileVisitResult.CONTINUE
        }
      }
    )
    files
  }

  def getGTFSFeeds(pathToR5Dir: String): Set[GTFSFeed] = {
    getAllGTFSFiles(pathToR5Dir)
      .map(file => GTFSFeed.fromFile(file.toString))
      .toSet
  }

  override def preLoad(): Unit = {
    this.frequencyData = loadFrequencyData()
  }

  override def postLoad(): Unit = {
    this.transportNetwork.transitLayer.buildDistanceTables(null)
    this.transportNetwork =
      buildFrequencyAdjustmentScenario(this.frequencyData).applyToTransportNetwork(transportNetwork)
  }
}
