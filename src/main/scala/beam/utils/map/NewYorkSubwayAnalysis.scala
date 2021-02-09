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
      """C:\temp\NY_runs\new-york-200k-baseline__2020-09-25_03-19-16_luj\10.routingResponse.parquet"""
    val walkTransitRequestToResponses = getWalkTransitRequestToResponses(pathToResponseFile)

    println(s"All possible modes: ${walkTransitRequestToResponses.flatMap(_._2.map(_.mode)).toSet}")
    println(
      s"All possible trip classifiers: ${walkTransitRequestToResponses.flatMap(_._2.map(_.tripClassifier)).toSet}"
    )

    showTransitDiversification(walkTransitRequestToResponses)

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

  private def showTransitDiversification(walkTransitRequestToResponses: Map[Int, Array[Data]]): Unit = {
    val temp = walkTransitRequestToResponses.values.map { xs =>
      val withoutBikes = noBikes(xs)
      val allLegsSorted =
        withoutBikes.groupBy(x => x.itineraryIndex).map { case (_, legs) => legs.sortBy(x => x.legIndex) }
      val tripClass = allLegsSorted.map { legs =>
        val uniqueModes = legs.map(_.mode).toSet
        val hasBus = uniqueModes.contains("bus")
        val hasSubway = uniqueModes.contains("subway")
        (hasBus, hasSubway, hasBus && hasSubway)
      }

      val anyBus = tripClass.exists { case (hasBus, hasSubway, _)    => hasBus && !hasSubway }
      val anySubway = tripClass.exists { case (hasBus, hasSubway, _) => hasSubway && !hasBus }
      val anyBoth = tripClass.exists { case (_, _, both)             => both }

      val onlyBusClass = tripClass.forall { case (hasBus, hasSubway, _)    => hasBus && !hasSubway }
      val onlySubwayClass = tripClass.forall { case (hasBus, hasSubway, _) => hasSubway && !hasBus }
      val onlyBothClass = tripClass.forall { case (_, _, both)             => both }
      val `bus + subway | subway Class` = anyBoth && anySubway && !anyBus
      val `bus + subway | bus Class` = anyBoth && anyBus && !anySubway
      val allClass = anyBoth && anySubway && anyBus

      if (onlyBusClass) "onlyBusClass"
      else if (onlySubwayClass) "onlySubwayClass"
      else if (onlyBothClass) "onlyBothClass"
      else if (`bus + subway | subway Class`) "bus + subway | subway Class"
      else if (`bus + subway | bus Class`) "bus + subway | bus Class"
      else if (allClass) "allClass"
      else "Others"
    }

    val default = Map(
      "onlyBusClass"                -> 0,
      "onlySubwayClass"             -> 0,
      "onlyBothClass"               -> 0,
      "bus + subway | subway Class" -> 0,
      "bus + subway | bus Class"    -> 0,
      "allClass"                    -> 0,
      "Others"                      -> 0
    )
    val transitDiversifivation =
      (default ++ temp.groupBy(identity).map { case (clazz, xs) => clazz -> xs.size }).toSeq.sortBy(x => x._1)
    println(s"transitDiversifivation: ${transitDiversifivation.size}")
    transitDiversifivation.foreach {
      case (clazz, cnt) =>
        println(s"${clazz},$cnt")
    }

    val csvKeys = transitDiversifivation.map(_._1).map(x => "\"" + x + "\"").mkString(",")
    println(csvKeys)

    val csvVals = transitDiversifivation.map(_._2).map(x => "\"" + x + "\"").mkString(",")
    println(csvVals)
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
