package beam.router.gtfs

import java.nio.file.{Path, Paths}

import beam.sim.config.BeamConfig
import org.onebusaway.csv_entities.schema.BeanWrapper
import org.onebusaway.gtfs.impl.GtfsRelationalDaoImpl
import org.onebusaway.gtfs.model.{AgencyAndId, Stop, StopTime, Trip}
import org.onebusaway.gtfs.serialization.GtfsReader
import org.onebusaway.gtfs.services.{GtfsMutableRelationalDao, GtfsRelationalDao}
import org.onebusaway.gtfs_transformer.GtfsTransformer
import org.onebusaway.gtfs_transformer.`match`.{EntityMatch, TypedEntityMatch}
import org.onebusaway.gtfs_transformer.deferred.ValueSetter
import org.onebusaway.gtfs_transformer.factory.{AddEntitiesTransformStrategy, EntitiesTransformStrategy}
import org.onebusaway.gtfs_transformer.services.{EntityTransformStrategy, GtfsTransformStrategy, TransformContext}

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap

/**
  * Loading of GTFS feeds should be done one-by-one, each for a separate instance of [[org.onebusaway.gtfs.services.GtfsRelationalDao]],
  * because if several feeds contain the same AgencyId then a [[org.onebusaway.gtfs.serialization.DuplicateEntityException]] could be thrown
  * @param beamConfig
  */
class GtfsLoader(beamConfig: BeamConfig) {
  import GtfsLoader._

  private val dataDirectory: Path = Paths.get(beamConfig.beam.routing.r5.directory)

  /**
    *
    * @param gtfsFeed could be either zip file or directory with GTFS data
    */
  def loadTripsFromGtfs(gtfsFeed: String): Seq[TripAndStopTimes] = {
    val dao = new GtfsRelationalDaoImpl

    val reader = new GtfsReader
    reader.setEntityStore(dao)
    reader.setInputLocation(dataDirectory.resolve(gtfsFeed).toFile)
    reader.run()

    getSortedTripsWithStopTimes(dao)
  }

  def transformGtfs(gtfsFeed: String, gtfsFeedOut: String, transformerStrategies: List[GtfsTransformStrategy]): Unit = {
    val transformer = new GtfsTransformer
    transformer.setGtfsInputDirectory(dataDirectory.resolve(gtfsFeed).toFile)
    transformer.setOutputDirectory(dataDirectory.resolve(gtfsFeedOut).toFile)
    transformerStrategies.foreach(transformer.addTransform)
    transformer.run()

    // getSortedTripsWithStopTimes(transformer.getDao)
  }

  private def getSortedTripsWithStopTimes(dao: GtfsRelationalDao) =
    dao.getAllTrips.asScala.toSeq
      .map(trip => TripAndStopTimes(trip, dao.getStopTimesForTrip(trip).asScala.sortBy(_.getStopSequence)))
      .sortBy(_.stopTimes.head.getArrivalTime)

  def findRepeatingTrips(tripsWithStopTimes: Seq[TripAndStopTimes]): TrieMap[String, Seq[(TripAndStopTimes, Int)]] = {
    tripsWithStopTimes
      .map { tripWithStopTimes =>
        val stopTimes = tripWithStopTimes.stopTimes

        stopTimes.tail
          .zip(stopTimes.init)
          .map {
            case (current, previous) =>
              val arrivalDiff = current.getArrivalTime - previous.getArrivalTime
              val departureDiff = current.getDepartureTime - previous.getDepartureTime
              (current, arrivalDiff, departureDiff)
          }
          .foldLeft(TripDiffAcc(tripWithStopTimes, Nil, Nil, Nil)) {
            case (acc, (stopTime, arrivalDiff, departureDiff)) =>
              acc.copy(
                stops = acc.stops :+ stopTime.getStop,
                arrivalDiffs = acc.arrivalDiffs :+ arrivalDiff,
                departureDiffs = acc.departureDiffs :+ departureDiff
              )
          }
      }
      .sortBy(_.trip.stopTimes.head.getArrivalTime)
      .foldLeft(HandledRepeatingAcc(Nil, TrieMap.empty)) {
        case (HandledRepeatingAcc(handledDiffs, repeatingTrips), diff) =>
          val (firstTrip, offsetSeconds) =
            handledDiffs
              .find(d => (d.trip != diff.trip && d.stops == diff.stops))
              .map(d => (d.trip, diff.trip.stopTimes.head.getArrivalTime - d.trip.stopTimes.head.getArrivalTime))
              .getOrElse((diff.trip, 0))
          val firstTripId = firstTrip.trip.getId.getId
          val repeatingTripsSeq =
            if (offsetSeconds == 0) Seq(firstTrip -> 0)
            else repeatingTrips.getOrElseUpdate(firstTripId, Seq()) :+ (diff.trip -> offsetSeconds)

          repeatingTrips.put(firstTripId, repeatingTripsSeq)
          HandledRepeatingAcc(handledDiffs :+ diff, repeatingTrips)
      }
      .repeatingTrips
  }

  def doubleTripsStrategy(
    repeatingTrips: TrieMap[String, Seq[(TripAndStopTimes, Int)]],
    multiplicator: Int = 2
  ): GtfsTransformStrategy = {
    val strategy = new AddEntitiesTransformStrategy
    val midnightArrivalTime = 86400 // seconds at midnight

    repeatingTrips.values.foreach { tripAndStopTimesWithOffset =>
      val trips = tripAndStopTimesWithOffset.map(_._1)
      // doubling trips between first stop and the last but one
      trips.tail
        .zip(trips.init)
        .foreach {
          case (current, previous) =>
            for (idx <- 1 until multiplicator) {
              val newTrip = createNewTrip(previous.trip, idx)
              strategy.addEntity(newTrip)

              previous.stopTimes
                .zip(current.stopTimes)
                .foreach {
                  case (prevStopTime, curStopTime) =>
                    val newStopTime = createStopTime(
                      newTrip,
                      prevStopTime.getDepartureTime + (curStopTime.getDepartureTime - prevStopTime.getDepartureTime) / multiplicator * idx,
                      prevStopTime.getArrivalTime + (curStopTime.getArrivalTime - prevStopTime.getArrivalTime) / multiplicator * idx,
                      prevStopTime.getStop,
                      prevStopTime.getStopSequence
                    )
                    strategy.addEntity(newStopTime)
                }
            }
            current
        }
      // doubling trips between last stop and the midnight
      val lastTrip = trips.last
      for { idx <- 1 until multiplicator } {
        val newTrip = createNewTrip(trips.last.trip, idx)
        strategy.addEntity(newTrip)

        val lastStopTimes = lastTrip.stopTimes
        val lastStopShift = midnightArrivalTime - lastStopTimes.last.getArrivalTime
        lastStopTimes.foreach { stopTime =>
          val newStopTime = createStopTime(
            newTrip,
            stopTime.getDepartureTime + lastStopShift / multiplicator * idx,
            stopTime.getArrivalTime + lastStopShift / multiplicator * idx,
            stopTime.getStop,
            stopTime.getStopSequence
          )
          strategy.addEntity(newStopTime)
        }
      }
    }

    strategy
  }

  def scaleTripsStrategy(
    repeatingTrips: TrieMap[String, Seq[(TripAndStopTimes, Int)]],
    scale: Double
  ): GtfsTransformStrategy = {
    val strategy = new EntitiesTransformStrategy

    repeatingTrips.values.foreach { tripAndStopTimesWithOffset =>
      val trips = tripAndStopTimesWithOffset.map(_._1)
      trips.foreach { trip =>
        // calculate new stop times (without first stop time - it remains as original)
        val offsetsBetweenStopTimes = trip.stopTimes.tail
          .zip(trip.stopTimes.init)
          .map {
            case (current, previous) =>
              (
                ((current.getArrivalTime - previous.getArrivalTime) * scale).toInt,
                ((current.getDepartureTime - previous.getDepartureTime) * scale).toInt
              )
          }
        // mutate all stop times and append their modifications to the strategy
        trip.stopTimes.tail
          .zip(trip.stopTimes.init)
          .zip(offsetsBetweenStopTimes)
          .map {
            case ((current, previous), (arrivalOffset, departureOffset)) =>
              current.setArrivalTime(previous.getArrivalTime + arrivalOffset)
              current.setDepartureTime(previous.getDepartureTime + departureOffset)
              strategy.addModification(
                new TypedEntityMatch(classOf[StopTime], new StopTimeMatch(current)),
                new StopTimeUpdateStrategy(current.getArrivalTime, current.getDepartureTime)
              )
          }
      }
    }
    strategy
  }

  private def createStopTime(
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

  private def createNewTrip(trip: Trip, idx: Int): Trip = {
    val newTrip = new Trip(trip)
    newTrip.setId(new AgencyAndId(trip.getId.getAgencyId, s"${trip.getId.getId}-clone-$idx"))
    newTrip
  }

}

object GtfsLoader {
  case class TripAndStopTimes(trip: Trip, stopTimes: Seq[StopTime])

  case class TripDiffAcc(
    trip: TripAndStopTimes,
    arrivalDiffs: List[Int],
    departureDiffs: List[Int],
    stops: List[Stop]
  )
  case class HandledRepeatingAcc(
    handledDiffs: List[TripDiffAcc],
    repeatingTrips: TrieMap[String, Seq[(TripAndStopTimes, Int)]]
  )
  case class FreqPattern(trip: Trip, prevTrip: Trip, offsetSeconds: Int)

  // additional classes for 'onebusaway' strategies
  class IntValueSetter(replacementValue: Integer) extends ValueSetter {
    override def setValue(bean: BeanWrapper, propertyName: String): Unit =
      if (replacementValue != null) bean.setPropertyValue(propertyName, replacementValue)
  }

  class StopTimeMatch(stopTime: StopTime) extends EntityMatch {
    override def isApplicableToObject(obj: Any): Boolean = obj match {
      case testStopTime: StopTime =>
        testStopTime.getTrip.getId == stopTime.getTrip.getId &&
        testStopTime.getId == stopTime.getId &&
        testStopTime.getStopSequence == stopTime.getStopSequence
      case _ => false
    }
  }

  class StopTimeUpdateStrategy(arrivalTime: Int, departureTime: Int) extends EntityTransformStrategy {
    override def run(context: TransformContext, dao: GtfsMutableRelationalDao, entity: Any): Unit = entity match {
      case stopTime: StopTime =>
        stopTime.setArrivalTime(arrivalTime)
        stopTime.setDepartureTime(departureTime)
      case _ => ()
    }
  }
}
