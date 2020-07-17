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
import scala.util.Random

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

      // 1. Read
      val dao = new GtfsRelationalDaoImpl

      val reader = new GtfsReader
      reader.setInputLocation(inputPath)
      reader.setEntityStore(dao)
      reader.run()

      // 2. Find Patterns
      val repeatingTrips = findRepeatingTrips(dao)

      // 3. Modify
      // for simplicity add one more in the middle between two buses (factor = 2, like * 0.5)
      // After the last bus on a route - the new one will be added between it and the midnight
      val lastStopSeconds = 86400 // midnight
      val factor = 2
      val strategy = new AddEntitiesTransformStrategy

      increateTripsOnRoutes(strategy, dao, repeatingTrips, factor, lastStopSeconds)

      val transformer = new GtfsTransformer
      transformer.setGtfsInputDirectory(inputPath)
      transformer.setOutputDirectory(outputPath)
      transformer.addTransform(strategy)

      transformer.run()

      // 4. Check outputPath
      val dao2 = new GtfsRelationalDaoImpl

      val reader2 = new GtfsReader
      reader2.setInputLocation(outputPath)
      reader2.setEntityStore(dao2)
      reader2.run()

      findRepeatingTrips(dao2)
    }

    def increateTripsOnRoutes(
      strategy: AddEntitiesTransformStrategy,
      dao: GtfsRelationalDao,
      repeatingTrips: TrieMap[String, Seq[(Trip, Int)]],
      factor: Int,
      lastStopArrivalTime: Int
    ) = {
      repeatingTrips.foreach {
        case (_, tripsWithOffset) =>
          val trips = tripsWithOffset.map(_._1)
          val lastTrip = trips.tail
            .foldLeft(trips.head) {
              case (previous, current) =>
                val newTrip = createNewTrip(previous)
                strategy.addEntity(newTrip)

                val lowStopTimes = dao.getStopTimesForTrip(previous).asScala
                val highStopTimes = dao.getStopTimesForTrip(current).asScala
                lowStopTimes
                  .zip(highStopTimes)
                  .foreach {
                    case (lowStopTime, highStopTime) =>
                      val newStopTime = createStopTime(
                        newTrip,
                        lowStopTime.getDepartureTime + (highStopTime.getDepartureTime - lowStopTime.getDepartureTime) / factor,
                        lowStopTime.getArrivalTime + (highStopTime.getArrivalTime - lowStopTime.getArrivalTime) / factor,
                        lowStopTime.getStop,
                        lowStopTime.getStopSequence
                      )
                      strategy.addEntity(newStopTime)
                  }
                current
            }
          // TODO move it into recursion
          val newTrip = createNewTrip(lastTrip)
          strategy.addEntity(newTrip)

          val lastStopTimes = dao.getStopTimesForTrip(lastTrip).asScala
          val lastStopShift = lastStopArrivalTime - lastStopTimes.last.getArrivalTime
          lastStopTimes.foreach { stopTime =>
            val newStopTime = createStopTime(
              newTrip,
              stopTime.getDepartureTime + lastStopShift / factor,
              stopTime.getArrivalTime + lastStopShift / factor,
              stopTime.getStop,
              stopTime.getStopSequence
            )
            strategy.addEntity(newStopTime)
          }
      }
    }

    def createStopTime(
      trip: Trip,
      departureTime: Int,
      arrivalTime: Int,
      stop: Stop,
      stopSequence: Int
    ): StopTime = {
      val stopTime = new StopTime
      stopTime.setTrip(trip)
      stopTime.setDepartureTime(departureTime)
      stopTime.setArrivalTime(arrivalTime)
      stopTime.setStop(stop)
      stopTime.setStopSequence(stopSequence)
      stopTime
    }

    def createNewTrip(trip: Trip): Trip = {
      val newTrip = new Trip(trip)
      newTrip.setId(new AgencyAndId(trip.getId.getAgencyId, trip.getId.getId + "-clone" + Random.nextInt(100)))
      newTrip
    }

    case class TripDiff(
      trip: Trip,
      current: StopTime,
      previous: Option[StopTime],
      arrivalDiffs: List[Int],
      departureDiffs: List[Int],
      stops: List[Stop]
    )
    case class FreqPattern(trip: Trip, prevTrip: Trip, offsetSeconds: Int)
    case class Acc(handledDiffs: List[TripDiff], repeatingTrips: TrieMap[String, Seq[(Trip, Int)]])

    def findRepeatingTrips(dao: GtfsRelationalDao): TrieMap[String, Seq[(Trip, Int)]] = {
      val tripDiffs = dao.getAllTrips.asScala.toList
        .flatMap { trip =>
          val stopTimes = dao.getStopTimesForTrip(trip).asScala
          stopTimes.headOption.map { firstStopTime =>
            stopTimes.tail
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
          }
        }
        .sortBy(_.current.getDepartureTime)

      val repeatingTrips = tripDiffs
        .foldLeft(Acc(Nil, TrieMap.empty)) {
          case (Acc(processedDiffs, repeatingTrips), diff) =>
            val (firstTrip, offsetSeconds) =
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
            val value = if (offsetSeconds == 0) {
              Seq(firstTrip -> 0)
            } else {
              val prevValue = repeatingTrips.getOrElseUpdate(key, Seq())
              prevValue :+ (diff.trip -> offsetSeconds)
            }

            repeatingTrips.put(key, value)

            Acc(processedDiffs :+ diff, repeatingTrips)
        }
        .repeatingTrips

      println("==============")
      println("All trips grouped by repeating")
      repeatingTrips.foreach(println)
      repeatingTrips
    }
  }
}
