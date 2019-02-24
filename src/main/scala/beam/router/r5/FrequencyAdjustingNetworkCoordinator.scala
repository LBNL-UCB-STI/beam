package beam.router.r5

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file._
import java.util

import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import com.conveyal.gtfs.GTFSFeed
import com.conveyal.gtfs.model.Trip
import com.conveyal.r5.analyst.scenario.{AddTrips, AdjustFrequency, Scenario}
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.network.Network

import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConverters._
import scala.io.Source

case class FrequencyAdjustingNetworkCoordinator(beamConfig: BeamConfig) extends NetworkCoordinator {

  override var transportNetwork: TransportNetwork = _
  override var network: Network = _
  lazy val feeds: Set[GTFSFeed] = getGTFSFeeds(beamConfig.beam.routing.r5.directory).toSet
  var frequencyData: Set[FrequencyAdjustmentInput] = _

  def loadFrequencyData(): Set[FrequencyAdjustmentInput] = {
    val lines = Source.fromFile(beamConfig.beam.agentsim.scenarios.frequencyAdjustmentFile).getLines().drop(1)
    val dataRows = for { line <- lines } yield {
      line.split(",")
    }.toSeq
    (for { row <- dataRows } yield {
      val (startTime: Int, endTime: Int) = row(1).toInt -> row(2).toInt
      // We assume that we are provided with a route Id. We need to convert to tripId for R5 Scenario Builder
      FrequencyAdjustmentInput(
        getTripIdForRouteIdAtTime(row.head, startTime, endTime),
        row(1).toInt,
        row(2).toInt,
        row(3).toInt,
        row(4).toInt
      )
    }).toSet
  }

  def buildFrequencyAdjustmentScenario(adjustmentInputs: Set[FrequencyAdjustmentInput]): Scenario = {
    val scenario = new Scenario()
    val adjustmentsByRouteId: Map[String, Set[FrequencyAdjustmentInput]] =
      adjustmentInputs.groupBy(adjustment => s"${feeds.head.feedId}:${getTripForId(adjustment.tripId).route_id}")

    util.Arrays.asList(adjustmentsByRouteId.foreach {
      case (rid, adjustments) =>
        val adjustFrequency: AdjustFrequency = new AdjustFrequency
        adjustFrequency.route = rid
        adjustFrequency.retainTripsOutsideFrequencyEntries = false
        val entries: util.Set[AddTrips.PatternTimetable] = adjustments.map { adjustmentInput =>
          adjustTripFrequency(adjustmentInput)
        }.asJava
        val listEntries: util.List[AddTrips.PatternTimetable] = new util.ArrayList[AddTrips.PatternTimetable]()
        listEntries.addAll(entries)
        adjustFrequency.entries = listEntries
        scenario.modifications.add(adjustFrequency)
    })

    scenario
  }

  def getTripForId(tripId: String): Trip = {
    feeds.map { feed =>
      feed.trips.asScala(tripId)
    }.head
  }

  def getTripIdForRouteIdAtTime(routeId: String, startTime: Int, endTime: Int): String = {
    feeds.map { feed =>
      val allTrips = feed.trips.asScala
      val routeTrips = allTrips.values.groupBy(_.route_id)(routeId)
      val orderedStops = routeTrips.flatMap{trip=>feed.getOrderedStopTimesForTrip(trip.trip_id).asScala.toVector}
      val orderedFilteredStops = orderedStops.filter { stopTime =>
        stopTime.arrival_time >= startTime
      }
      orderedFilteredStops.headOption.getOrElse(orderedStops.last).trip_id
    }.head
  }

  def adjustTripFrequency(adjustmentInput: FrequencyAdjustmentInput): AddTrips.PatternTimetable = {

    val entry = new AddTrips.PatternTimetable
    entry.headwaySecs = adjustmentInput.headwaySecs
    entry.startTime = adjustmentInput.startTime
    entry.endTime = adjustmentInput.endTime
    entry.sunday = false
    entry.monday = true
    entry.tuesday = true
    entry.wednesday = true
    entry.thursday = true
    entry.friday = true
    entry.saturday = false
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

  def getGTFSFeeds(pathToR5Dir: String): ArrayBuffer[GTFSFeed] = {
    getAllGTFSFiles(pathToR5Dir).map(file => GTFSFeed.fromFile(file.toString))
  }

  override def preprocessing(): Unit = {
    this.frequencyData = loadFrequencyData()
  }

  override def postProcessing(): Unit = {
    this.transportNetwork.transitLayer.buildDistanceTables(null)
    this.transportNetwork =
      buildFrequencyAdjustmentScenario(this.frequencyData).applyToTransportNetwork(transportNetwork)
    convertFrequenciesToTrips()
  }
}
