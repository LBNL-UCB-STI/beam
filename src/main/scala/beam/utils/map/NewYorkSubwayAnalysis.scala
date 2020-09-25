package beam.utils.map

import beam.utils.ParquetReader
import org.apache.avro.generic.GenericRecord

object NewYorkSubwayAnalysis {
  private case class Data(
    requestId: Int,
    tripClassifier: String,
    itineraryIndex: Int,
    mode: String,
    legIndex: Int,
    startTime: Int
  )

  def getNumberOf(walkTransitResponses: Map[Int, Array[Data]], modes: Set[String]): Int = {
    walkTransitResponses.count {
      case (_, xs) =>
        val uniqueModes = xs.map(_.mode).toSet
        uniqueModes == modes
    }
  }

  def getNumberOfBuses(walkTransitRequestToResponses: Map[Int, Array[Data]]): Int = {
    getNumberOf(walkTransitRequestToResponses, Set("walk", "bus"))
  }

  def getNumberOfSubways(walkTransitRequestToResponses: Map[Int, Array[Data]]): Int = {
    getNumberOf(walkTransitRequestToResponses, Set("walk", "subway"))
  }

  def getNumberOfBusAndSubways(walkTransitRequestToResponses: Map[Int, Array[Data]]): Int = {
    getNumberOf(walkTransitRequestToResponses, Set("walk", "subway", "bus"))
  }

  def main(args: Array[String]): Unit = {
    val pathToResponseFile =
      "C:/temp/NY_runs/NYC-200k-bus-vs-subway-more-samples-2__2020-09-23_14-51-18_ret/0.routingResponse.parquet"
    val walkTransitRequestToResponses = getWalkTransitRequestToResponses(pathToResponseFile)

    val totalWalkTransits = walkTransitRequestToResponses.map(x => x._2.map(_.itineraryIndex).distinct.length).sum
    println(s"totalWalkTransits: ${totalWalkTransits}")

    val moreThanOneTransit = walkTransitRequestToResponses.count {
      case (_, xs) =>
        // Shouldn't consider bike because it become BIKE_TRANSIT
        val itineraryIndices = xs.filter(x => x.mode == "bike").map(_.itineraryIndex).toSet
        val nWalkTransits = xs
          .filter(x => !itineraryIndices.contains(x.itineraryIndex))
          .count(x => x.tripClassifier == "walk_transit" && x.legIndex == 0)
        nWalkTransits > 1
    }
    println(s"The number of cases when there are more than one transit: ${moreThanOneTransit}")

    val onlyBusCount: Int = getNumberOfBuses(walkTransitRequestToResponses)
    println(s"Contains only BUS mode: $onlyBusCount")

    val onlySubwayCount: Int = getNumberOfSubways(walkTransitRequestToResponses)
    println(s"Contains only SUBWAY mode: $onlySubwayCount")

    val busAndSubwayCount: Int = getNumberOfBusAndSubways(walkTransitRequestToResponses)
    println(s"Contains both BUS and SUBWAY: $busAndSubwayCount")

  }

  private def getWalkTransitRequestToResponses(pathToResponeFile: String): Map[Int, Array[Data]] = {
    def walkTransitFilter(record: GenericRecord): Boolean = {
      record.get("tripClassifier").toString == "walk_transit"
    }

    val walkTransitResponses = {
      val (it, toClose) = ParquetReader.read(pathToResponeFile)
      try {
        it.filter(walkTransitFilter).toArray
      } finally {
        toClose.close()
      }
    }
    val result = walkTransitResponses
      .map { record =>
        val requestId = record.get("requestId").asInstanceOf[Int]
        val tripClassifier = record.get("tripClassifier").toString
        val itineraryIndex = record.get("itineraryIndex").asInstanceOf[Int]
        val mode = record.get("mode").toString
        val legIndex = record.get("legIndex").asInstanceOf[Int]
        val startTime = record.get("startTime").asInstanceOf[Int]
        Data(requestId, tripClassifier, itineraryIndex, mode, legIndex, startTime)
      }
      .groupBy { x =>
        x.requestId
      }
      .map { case (reqId, xs) => reqId -> xs.sortBy(x => (x.itineraryIndex, x.legIndex)) }
    result
  }
}
