package beam.utils.map

import beam.utils.csv.CsvWriter
import beam.utils.{DebugLib, ParquetReader, Statistics}
import org.apache.avro.generic.GenericRecord

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

object NewYorkSubwayAnalysis {
  private case class Data(
    requestId: Int,
    tripClassifier: String,
    itineraryIndex: Int,
    mode: String,
    legIndex: Int,
    startTime: Int,
    dinstanceInM: Double
  )

  def getNumberOf(walkTransitResponses: Map[Int, Array[Data]], modes: Set[String]): Int = {
    walkTransitResponses.map {
      case (_, xs) =>
        val noBikeTransit = noBikes(xs)
        val areMatching = noBikeTransit
          .groupBy(x => x.itineraryIndex)
          .map { case (_, xs) => modes == xs.map(_.mode).toSet }
        areMatching.count(x => x)
    }.sum
  }

  private def noBikes(xs: Array[Data]): Array[Data] = {
    val itineraryIndices = xs.filter(x => x.mode == "bike").map(_.itineraryIndex).toSet
    val noBikeTransit = xs
      .filter(x => !itineraryIndices.contains(x.itineraryIndex))
    noBikeTransit
  }

  def getNumberOfBuses(walkTransitRequestToResponses: Map[Int, Array[Data]]): Int = {
    getNumberOf(walkTransitRequestToResponses, Set("walk", "bus"))
  }

  def getNumberOfSubways(walkTransitRequestToResponses: Map[Int, Array[Data]]): Int = {
    getNumberOf(walkTransitRequestToResponses, Set("walk", "subway"))
  }

  def getNumberOfTrams(walkTransitRequestToResponses: Map[Int, Array[Data]]): Int = {
    getNumberOf(walkTransitRequestToResponses, Set("walk", "tram"))
  }

  def getNumberOfFerries(walkTransitRequestToResponses: Map[Int, Array[Data]]): Int = {
    getNumberOf(walkTransitRequestToResponses, Set("walk", "ferry"))
  }

  def getNumberOfRails(walkTransitRequestToResponses: Map[Int, Array[Data]]): Int = {
    getNumberOf(walkTransitRequestToResponses, Set("walk", "rail"))
  }

  def getNumberOfBusAndSubways(walkTransitRequestToResponses: Map[Int, Array[Data]]): Int = {
    getNumberOf(walkTransitRequestToResponses, Set("walk", "subway", "bus"))
  }

  def main(args: Array[String]): Unit = {
    val pathToResponseFile =
      "C:/temp/NY_runs/new-york-200k-baseline__2020-09-25_03-19-16_luj/10.routingResponse.parquet"
    val walkTransitRequestToResponses = getWalkTransitRequestToResponses(pathToResponseFile)

    println(s"All possible modes: ${walkTransitRequestToResponses.flatMap(_._2.map(_.mode)).toSet}")
    println(
      s"All possible trip classifiers: ${walkTransitRequestToResponses.flatMap(_._2.map(_.tripClassifier)).toSet}"
    )

    val allWalkDistancesBetweenTwoSubways = walkTransitRequestToResponses.values.flatMap { xs =>
      val withoutBikes = noBikes(xs)
      val allLegsSorted =
        withoutBikes.groupBy(x => x.itineraryIndex).map { case (_, legs) => legs.sortBy(x => x.legIndex) }
      allLegsSorted.flatMap { legs =>
        val x = getWalkBetweenTwoSubways(legs)
        if (x.length > 1) {
          DebugLib.emptyFunctionForSettingBreakPoint()
        }
        x
      }
    }
    println(s"allWalkDistancesBetweenTwoSubways: ${allWalkDistancesBetweenTwoSubways.size}")
    println(s"Statistics(allWalkDistancesBetweenTwoSubways): ${Statistics(allWalkDistancesBetweenTwoSubways.toSeq)}")

    val csvWriter = new CsvWriter("walk_between_subways.csv", Array("distanceInM"))
    allWalkDistancesBetweenTwoSubways.foreach { d =>
      csvWriter.write(d)
    }
    csvWriter.close()

    val totalWalkTransits = walkTransitRequestToResponses.map(x => x._2.map(_.itineraryIndex).distinct.length).sum

    val moreThanOneTransit = walkTransitRequestToResponses.count {
      case (_, xs) =>
        // Shouldn't consider bike because it become BIKE_TRANSIT
        val nWalkTransits = noBikes(xs).count(x => x.tripClassifier == "walk_transit" && x.legIndex == 0)
        nWalkTransits > 1
    }

    println(s"File: $pathToResponseFile")
    println(s"totalWalkTransits: ${totalWalkTransits}")

    println(s"The number of cases when there are more than one transit: ${moreThanOneTransit}")

    val onlyBusCount: Int = getNumberOfBuses(walkTransitRequestToResponses)
    println(s"Contains only BUS mode: $onlyBusCount")

    val onlySubwayCount: Int = getNumberOfSubways(walkTransitRequestToResponses)
    println(s"Contains only SUBWAY mode: $onlySubwayCount")

    val onlyTramCount: Int = getNumberOfTrams(walkTransitRequestToResponses)
    println(s"Contains only TRAM mode: $onlyTramCount")

    val onlyFerryCount: Int = getNumberOfFerries(walkTransitRequestToResponses)
    println(s"Contains only Ferry mode: $onlyFerryCount")

    val onlyRailCount: Int = getNumberOfRails(walkTransitRequestToResponses)
    println(s"Contains only Rail mode: $onlyFerryCount")

    val busAndSubwayCount: Int = getNumberOfBusAndSubways(walkTransitRequestToResponses)
    println(s"Contains both BUS and SUBWAY: $busAndSubwayCount")

    val csvStr = Array(
      totalWalkTransits,
      moreThanOneTransit,
      onlyBusCount,
      onlySubwayCount,
      onlyTramCount,
      onlyFerryCount,
      onlyRailCount,
      busAndSubwayCount
    ).map(x => "\"" + x + "\"").mkString(",")
    println(s"Csv: $csvStr")

  }

  private def getWalkBetweenTwoSubways(legs: Array[Data]): Seq[Double] = {
    @tailrec
    def getWalkBetweenTwoSubways0(index: Int, result: ArrayBuffer[Double]): Seq[Double] = {
      legs.zipWithIndex.find { case (x, _) => x.legIndex >= index && x.mode == "walk" } match {
        case Some((leg, index)) =>
          val prev = legs.lift(index - 1)
          val next = legs.lift(index + 1)
          val maybeWalkDistanceBetweenSubways = for {
            p <- prev
            n <- next
            if p.mode == "subway" && n.mode == "subway"
          } yield leg.dinstanceInM
          maybeWalkDistanceBetweenSubways.foreach(result += _)
          getWalkBetweenTwoSubways0(index + 1, result)
        case None =>
          None
          result
      }
    }
    getWalkBetweenTwoSubways0(1, ArrayBuffer.empty)
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
        val dinstanceInM = record.get("distanceInM").asInstanceOf[Double]
        Data(requestId, tripClassifier, itineraryIndex, mode, legIndex, startTime, dinstanceInM)
      }
      .groupBy { x =>
        x.requestId
      }
      .map { case (reqId, xs) => reqId -> xs.sortBy(x => (x.itineraryIndex, x.legIndex)) }
    result
  }
}
