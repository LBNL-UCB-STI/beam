package beam.utils.analysis

import java.io.Closeable

import beam.agentsim.events.PathTraversalEvent
import beam.utils.EventReader
import beam.utils.csv.CsvWriter
import org.matsim.api.core.v01.events.Event
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader

object PathTraversalAnalysis {

  def main(args: Array[String]): Unit = {

    val eventsFile = args(0) //"/home/rajnikant/Desktop/beam/output/sf-light/sf-light-1k-xml__2020-07-08_21-23-07_nuj/ITERS/it.0/0.events.csv"
    val networkFile = args(1) //"/home/rajnikant/Desktop/beam/output/sf-light/sf-light-1k-xml__2020-07-08_21-23-07_nuj/output_network.xml.gz"
    val outputPath = args(2) //"/home/rajnikant/Desktop/beam/output/sf-light/"
    val outputFile = "vehicleAnalysis.csv"

    val network = NetworkUtils.createNetwork()
    new MatsimNetworkReader(network).readFile(networkFile)

    val (events: Iterator[Event], closable: Closeable) = EventReader.fromCsvFile(eventsFile, _ => true)

    writeCSVRows(events, s"$outputPath$outputFile")
  }

  def writeCSVRows(events: Iterator[Event], path: String): Unit = {
    val pathTraversalEvents = events
      .filter(_.getEventType == "PathTraversal")
      .map(PathTraversalEvent(_))
      .filter(_.mode.isTransit)
      .toList

    val analysis =
      pathTraversalEvents.groupBy(_.vehicleId.toString).map { case (k, v) => k -> v.map(_.numberOfPassengers.toString) }
    val max = analysis.values.map(_.size).max
    val resultData = analysis.map { case (k, v) => k -> getList(max, v) }
    val values = resultData.values.transpose
    val headers = analysis.keys.toIndexedSeq

    val csvWriter = new CsvWriter(path, headers)
    values.foreach(v => { csvWriter.writeRow(v.toIndexedSeq) })

    csvWriter.close()
  }

  def getList(maxSize: Int, list: List[String]): List[String] = {
    list ::: List.fill(maxSize - list.size)("")
  }
}
