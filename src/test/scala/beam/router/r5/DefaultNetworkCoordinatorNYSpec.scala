package beam.router.r5

import java.io.File

import org.onebusaway.gtfs.impl.GtfsRelationalDaoImpl
import org.onebusaway.gtfs.model.{AgencyAndId, Stop, StopTime, Trip}
import org.onebusaway.gtfs.serialization.GtfsReader
import org.onebusaway.gtfs.services.GtfsRelationalDao
import org.onebusaway.gtfs_transformer.GtfsTransformer
import org.onebusaway.gtfs_transformer.factory.AddEntitiesTransformStrategy
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap

class DefaultNetworkCoordinatorNYSpec
    extends WordSpecLike
    with Matchers
    with MockitoSugar
    with TableDrivenPropertyChecks {

  private val rootPath = new File(getClass.getResource("/r5-ny").getPath).getAbsolutePath

  "DefaultNetworkCoordinator (for NY)" should { // TODO change name

    "transform gtfs" in {
      // new File(s"$rootPath/nyc-gtfs")
      // new File(s"$rootPath/Long_Island_Rail_20200215.zip")

      val inputPath = new File(s"$rootPath/bus")
      val outputPath = new File(s"$rootPath/bus-out")

      // 1. READ
      val readerDao = new GtfsRelationalDaoImpl

      val reader = new GtfsReader
      reader.setInputLocation(inputPath)
      reader.setEntityStore(readerDao)
      reader.run()

      // print source data
      // printAllData(readerDao)

      // 2. Find Patterns
      findRepeatingTrips(readerDao)

      // 3. MODIFY
      // add stop times
      val stopTime1 = createStopTime(readerDao, "bus", "B1-EAST-1-1", 5000, 4000, "1", 1)
      val stopTime2 = createStopTime(readerDao, "bus", "B1-EAST-1-1", 6000, 7000, "2", 2)

      val strategy = new AddEntitiesTransformStrategy
      strategy.addEntity(stopTime1)
      strategy.addEntity(stopTime2)

      val transformer = new GtfsTransformer
      transformer.setGtfsInputDirectory(inputPath)
      transformer.setOutputDirectory(outputPath)

      transformer.addTransform(strategy)
      transformer.run()

      // 4. PRINT DATA
      val transformerDao = transformer.getDao

      // print transformed data
      // printAllData(transformerDao)

    }

    def createStopTime(
      dao: GtfsRelationalDao,
      agency: String,
      tripId: String,
      departureTime: Int,
      arrivalTime: Int,
      stopId: String,
      stopSeqNumber: Int
    ): StopTime = {
      val stopTime = new StopTime
      stopTime.setTrip(dao.getTripForId(new AgencyAndId(agency, tripId)))
      stopTime.setDepartureTime(departureTime)
      stopTime.setArrivalTime(arrivalTime)
      stopTime.setStop(dao.getStopForId(new AgencyAndId(agency, stopId)))
      stopTime.setStopSequence(stopSeqNumber)
      stopTime
    }

    def printDiff(firstStopTime: StopTime, diffs: TripDiff, lastStopTime: StopTime) = {
      println("arrivals diffs: " + diffs.arrivalDiffs)
      println("departures  diffs: " + diffs.departureDiffs)
      println("first stop:" + firstStopTime)
      println("last stop:" + lastStopTime)
      println("---")
    }

    def printAllData(dao: GtfsRelationalDao): Unit = {
      printCollection("Agencies", dao.getAllAgencies)
      printCollection("CalendarDates", dao.getAllCalendarDates)
      printCollection("FeedInfos", dao.getAllFeedInfos)
      printCollection("Frequencies", dao.getAllFrequencies)
      printCollection("Routes", dao.getAllRoutes)
      printCollection("ShapeIds", dao.getAllShapeIds)
      printCollection("ShapePoints", dao.getAllShapePoints)
      printCollection("StopTimes", dao.getAllStopTimes)
      printCollection("Stops", dao.getAllStops)
      printCollection("Trips", dao.getAllTrips)
    }

    def printCollection[A](name: String, c: java.util.Collection[A]): Unit = {
      println(s"$name(${c.size}): ${c.toArray.take(100).mkString(", ")}")
    }

    def printCollectionS[A](name: String, c: scala.collection.Seq[A]): Unit = {
      println(s"$name(${c.size}): ${c.take(100).mkString(", ")}")
    }

    // model
    case class TripDiff(
      trip: Trip,
      current: StopTime,
      previous: Option[StopTime],
      arrivalDiffs: List[Int],
      departureDiffs: List[Int],
      stops: List[Stop]
    )

    case class FreqPattern(trip: Trip, prevTrip: Trip, headwaySeconds: Int)
    case class Acc(handledDiffs: List[TripDiff], repeatingTrips: TrieMap[String, Seq[(Trip, Int)]])

    def findRepeatingTrips(dao: GtfsRelationalDao) = {
      val tripDiffs = dao.getAllTrips.asScala
        .map { trip =>
          val stopTimes = dao.getStopTimesForTrip(trip).asScala
          // printCollectionS(s"stop stopTimes for $trip", stopTimes)

          val firstStopTime = stopTimes.head // TODO headOption?
          val diffs = stopTimes.tail
            .foldLeft(TripDiff(trip, firstStopTime, None, Nil, Nil, List(firstStopTime.getStop))) {
              case (diff, nextStopTime) =>
                val currentStopTime = diff.current
                val arrivalDiff = nextStopTime.getArrivalTime - currentStopTime.getArrivalTime
                val departureDiff = nextStopTime.getDepartureTime - currentStopTime.getDepartureTime
                diff.copy(
                  current = nextStopTime,
                  previous = Some(currentStopTime),
                  arrivalDiffs = diff.arrivalDiffs :+ arrivalDiff,
                  departureDiffs = diff.departureDiffs :+ departureDiff,
                  stops = diff.stops :+ nextStopTime.getStop
                )
            }
          // printDiff(firstStopTime, diffs, lastStopTime = diffs.current)
          diffs
        }
        .toList
        .sortBy(_.current.getDepartureTime)

      println(s"All trip diffs: \r\n${tripDiffs.mkString("\r\n")}")

      val repeatingTripsWithHeadwaySeconds = tripDiffs
        .foldLeft(Acc(Nil, TrieMap.empty)) {
          case (Acc(processedDiffs, repeatingTrips), diff) =>
            val (firstTrip, headwaySeconds) =
              processedDiffs
                .find { d =>
                  (d.trip != diff.trip
                  && d.arrivalDiffs == diff.arrivalDiffs
                  && d.departureDiffs == diff.departureDiffs
                  && d.stops == diff.stops)
                }
                .map(d => (d.trip, diff.current.getArrivalTime - d.current.getArrivalTime))
                .getOrElse((diff.trip, 0))

            val key = firstTrip.getId.getId
            val value = if (headwaySeconds == 0) {
              Seq(firstTrip -> 0)
            } else {
              val prevValue = repeatingTrips.getOrElseUpdate(key, Seq())
              prevValue :+ (diff.trip -> headwaySeconds)
            }

            repeatingTrips.put(key, value)

            Acc(processedDiffs :+ diff, repeatingTrips)
        }
        .repeatingTrips

      println("==============")
      println("Repeating trips (found patterns)")
      repeatingTripsWithHeadwaySeconds.foreach(println)
      println("==============")
      repeatingTripsWithHeadwaySeconds
    }

  }
}
