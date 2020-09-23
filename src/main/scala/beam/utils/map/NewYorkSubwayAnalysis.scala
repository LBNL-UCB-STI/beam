package beam.utils.map

import beam.utils.ParquetReader
import org.apache.avro.generic.GenericRecord

object NewYorkSubwayAnalysis {
  private case class Data(requestId: Int, mode: String, legIndex: Int, startTime: Int)

  def getNumberOf(walkTransitResponses: Map[Int, Array[Data]], modes: Set[String]): Int = {
    walkTransitResponses.count {
      case (_, xs) =>
        val uniqueModes = xs.map(_.mode).toSet
        uniqueModes == modes
    }
  }

  def getNumberOfBuses(walkTransitResponses: Map[Int, Array[Data]]): Int = {
    getNumberOf(walkTransitResponses, Set("walk", "bus"))
  }

  def getNumberOfSubways(walkTransitResponses: Map[Int, Array[Data]]): Int = {
    getNumberOf(walkTransitResponses, Set("walk", "subway"))
  }

  def getNumberOfBusAndSubways(walkTransitResponses: Map[Int, Array[Data]]): Int = {
    getNumberOf(walkTransitResponses, Set("walk", "subway", "bus"))
  }

  def main(args: Array[String]): Unit = {
    val pathToResponseFile =
      "C:/temp/NY_runs/NYC-200k-bus-vs-subway-1__2020-09-18_21-41-55_bac/10.routingResponse.parquet"
    val walkTransitResponses = getAllWalkTransitResponses(pathToResponseFile)
    println(s"walkTransitResponses: ${walkTransitResponses.size}")

    val onlyBusCount: Int = getNumberOfBuses(walkTransitResponses)
    println(s"Contains only BUS mode: $onlyBusCount")

    val onlySubwayCount: Int = getNumberOfSubways(walkTransitResponses)
    println(s"Contains only SUBWAY mode: $onlySubwayCount")

    val busAndSubwayCount: Int = getNumberOfBusAndSubways(walkTransitResponses)
    println(s"Contains both BUS and SUBWAY: $busAndSubwayCount")
  }

  private def getAllWalkTransitResponses(pathToResponeFile: String): Map[Int, Array[Data]] = {
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
        val mode = record.get("mode").toString
        val legIndex = record.get("legIndex").asInstanceOf[Int]
        val startTime = record.get("startTime").asInstanceOf[Int]
        Data(requestId, mode, legIndex, startTime)
      }
      .groupBy { x =>
        x.requestId
      }
      .map { case (reqId, xs) => reqId -> xs }
    result
  }
}
