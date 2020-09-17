package beam.utils.transit

import java.nio.file.Path

import org.onebusaway.csv_entities.schema.BeanWrapper
import org.onebusaway.gtfs.impl.GtfsRelationalDaoImpl
import org.onebusaway.gtfs.model.{AgencyAndId, Frequency, Route, Stop, StopTime, Trip}
import org.onebusaway.gtfs.serialization.GtfsReader
import org.onebusaway.gtfs.services.{GtfsMutableRelationalDao, GtfsRelationalDao}
import org.onebusaway.gtfs_transformer.GtfsTransformer
import org.onebusaway.gtfs_transformer.`match`.{EntityMatch, TypedEntityMatch}
import org.onebusaway.gtfs_transformer.deferred.ValueSetter
import org.onebusaway.gtfs_transformer.factory.{AddEntitiesTransformStrategy, EntitiesTransformStrategy}
import org.onebusaway.gtfs_transformer.impl.RemoveEntityUpdateStrategy
import org.onebusaway.gtfs_transformer.services.{EntityTransformStrategy, GtfsTransformStrategy, TransformContext}
import org.onebusaway.gtfs_transformer.updates.UpdateLibrary

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.util.Random

/**
  * Loading of GTFS feeds should be done one-by-one, each for a separate instance of [[org.onebusaway.gtfs.services.GtfsRelationalDao]],
  * because if several feeds contain the same AgencyId then a [[org.onebusaway.gtfs.serialization.DuplicateEntityException]] could be thrown
  */
object GtfsUtils {

  /**
    * Loads GTFS feed into sequence of trips with their stop times
    * @param gtfsFeed could be either zip file or directory with GTFS data
    */
  def loadTripsFromGtfs(gtfsFeed: Path): (Seq[TripAndStopTimes], GtfsMutableRelationalDao) = {
    val dao = new GtfsRelationalDaoImpl

    val reader = new GtfsReader
    reader.setEntityStore(dao)
    reader.setInputLocation(gtfsFeed.toFile)
    reader.run()

    (getSortedTripsWithStopTimes(dao), dao)
  }

  /**
    * Loads GTFS feed into sequence of trips with their stop times
    * and applies transformation strategies
    * @param gtfsFeed path could be either zip file or directory with GTFS data
    * @param gtfsFeedOut path could be either zip file or directory with GTFS data
    * @param transformerStrategies list of transform strategies to be applied to GTFS entities
    */
  def transformGtfs(
    gtfsFeed: Path,
    gtfsFeedOut: Path,
    transformerStrategies: List[GtfsTransformStrategy]
  ): Seq[TripAndStopTimes] = {
    val transformer = new GtfsTransformer
    transformer.setGtfsInputDirectory(gtfsFeed.toFile)
    transformer.setOutputDirectory(gtfsFeedOut.toFile)
    transformerStrategies.foreach(transformer.addTransform)
    transformer.run()

    getSortedTripsWithStopTimes(transformer.getDao)
  }

  private def getSortedTripsWithStopTimes(dao: GtfsRelationalDao) =
    dao.getAllTrips.asScala.toSeq
      .map(trip => TripAndStopTimes(trip, dao.getStopTimesForTrip(trip).asScala.sortBy(_.getStopSequence)))
      .sortBy(_.stopTimes.head.getArrivalTime)

  private[transit] def findRepeatingTrips(
    tripsWithStopTimes: Seq[TripAndStopTimes],
    sameServiceOnly: Boolean = true
  ): TrieMap[String, Seq[(TripAndStopTimes, Int)]] = {
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
              .find { d =>
                d.trip != diff.trip &&
                d.stops == diff.stops &&
                (if (sameServiceOnly) d.trip.trip.getServiceId == diff.trip.trip.getServiceId else true)
              }
              .map(d => (d.trip, diff.trip.stopTimes.head.getArrivalTime - d.trip.stopTimes.head.getArrivalTime))
              .getOrElse((diff.trip, 0))

          val firstTripId = firstTrip.trip.getId.getId
          val repeatingTripsSeq =
            if (firstTrip == diff.trip) Seq(firstTrip -> 0)
            else repeatingTrips.getOrElseUpdate(firstTripId, Seq(firstTrip -> 0)) :+ (diff.trip -> offsetSeconds)

          repeatingTrips.put(firstTripId, repeatingTripsSeq)
          HandledRepeatingAcc(handledDiffs :+ diff, repeatingTrips)
      }
      .repeatingTrips
  }

  def doubleTripsStrategy(
    dao: GtfsMutableRelationalDao,
    tripsWithStopTimes: Seq[TripAndStopTimes],
    factor: Float = 2.0f,
    timeFrame: TimeFrame = TimeFrame.WholeDay
  ): GtfsTransformStrategy = {
    assert(factor >= 1.0)

    val strategy = new AddEntitiesTransformStrategy
    val lastArrivalTime = timeFrame.endTime

    val foundTrips = findTrips(tripsWithStopTimes, timeFrame)
    val allTrips = foundTrips.flatten
    val totalNum = allTrips.size
    val numExtraTrips = Math.round(totalNum * (factor - 1)) % totalNum
    val (specialTrips, regularTrips) = Random.shuffle(allTrips).splitAt(numExtraTrips)
    val factors = (specialTrips.map(tripAndStopTimes => tripAndStopTimes.trip -> (factor.toInt + 1))
    ++ regularTrips.map(tripAndStopTimes => tripAndStopTimes.trip             -> factor.toInt)).toMap

    foundTrips
      .foreach { trips =>
        // doubling trips between first stop and the last but one
        trips.tail
          .zip(trips.init)
          .foreach {
            case (current, previous) =>
              val factor = factors(previous.trip)
              for (idx <- 1 until factor) {
                val (newTrip, newFrequencies) = createNewTrip(previous.trip, idx, dao)
                strategy.addEntity(newTrip)
                newFrequencies.foreach(strategy.addEntity)

                previous.stopTimes
                  .zip(current.stopTimes)
                  .foreach {
                    case (prevStopTime, curStopTime) =>
                      val newStopTime = createStopTime(
                        newTrip,
                        prevStopTime.getDepartureTime + (curStopTime.getDepartureTime - prevStopTime.getDepartureTime) / factor * idx,
                        prevStopTime.getArrivalTime + (curStopTime.getArrivalTime - prevStopTime.getArrivalTime) / factor * idx,
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
        val factor = factors(lastTrip.trip)
        for { idx <- 1 until factor } {
          val (newTrip, newFrequencies) = createNewTrip(lastTrip.trip, idx, dao)
          newFrequencies.foreach(strategy.addEntity)
          strategy.addEntity(newTrip)

          val lastStopTimes = lastTrip.stopTimes
          val lastStopShift = lastArrivalTime - lastStopTimes.last.getArrivalTime
          lastStopTimes.foreach { stopTime =>
            val newStopTime = createStopTime(
              newTrip,
              stopTime.getDepartureTime + lastStopShift / factor * idx,
              stopTime.getArrivalTime + lastStopShift / factor * idx,
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
    tripsWithStopTimes: Seq[TripAndStopTimes],
    scale: Double,
    timeFrame: TimeFrame = TimeFrame.WholeDay
  ): GtfsTransformStrategy = {
    val strategy = new EntitiesTransformStrategy

    findTrips(tripsWithStopTimes, timeFrame)
      .foreach { trips =>
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

  def removeTripsStrategy(
    tripsWithStopTimes: Seq[TripAndStopTimes],
    factor: Float = 0.5f,
    timeFrame: TimeFrame = TimeFrame.WholeDay
  ): GtfsTransformStrategy = {
    assert(factor < 1.0)
    val allTrips = tripsWithStopTimes
      .filter(_.stopTimes.head.getArrivalTime >= timeFrame.startTime)
      .filter(_.stopTimes.last.getDepartureTime <= timeFrame.endTime)
      .map(_.trip)

    val allRoutes = allTrips.map(_.getRoute).toSet
    val numToDelete = Math.round(allTrips.size * (1 - factor))
    val (toDelete, survived) = Random.shuffle(allTrips).splitAt(numToDelete)
    val deletedRoutes = allRoutes -- survived.map(_.getRoute).toSet

    val removeEntityUpdateStrategy = new RemoveEntityUpdateStrategy()
    val resultStrategy = new EntitiesTransformStrategy()
    deletedRoutes.foreach { route =>
      resultStrategy.addModification(new TypedEntityMatch(classOf[Route], {
        case rt: Route => rt.getId == route.getId
        case _         => false
      }), removeEntityUpdateStrategy)
    }
    toDelete.foreach { trip =>
      resultStrategy.addModification(new TypedEntityMatch(classOf[Trip], {
        case trp: Trip => trp.getId == trip.getId
        case _         => false
      }), removeEntityUpdateStrategy)
    }
    resultStrategy
  }

  private def findTrips(
    tripsWithStopTimes: Seq[TripAndStopTimes],
    timeFrame: TimeFrame
  ): Seq[Seq[TripAndStopTimes]] = {
    val repeatingTrips = findRepeatingTrips(tripsWithStopTimes)
    repeatingTrips.values.view
      .map {
        _.view
          .map(_._1)
          .filter(_.stopTimes.head.getArrivalTime >= timeFrame.startTime)
          .filter(_.stopTimes.last.getDepartureTime <= timeFrame.endTime)
          .toSeq
      }
      .filter(_.nonEmpty)
      .toSeq
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

  private def createNewTrip(trip: Trip, idx: Int, dao: GtfsMutableRelationalDao): (Trip, List[Frequency]) = {
    val newTrip = new Trip(trip)
    newTrip.setId(new AgencyAndId(trip.getId.getAgencyId, s"${trip.getId.getId}-clone-$idx"))

    val frequencies = dao.getFrequenciesForTrip(trip)
    val newFrequencies = frequencies.asScala.map { frequency =>
      val newFrequency = new Frequency(frequency)
      newFrequency.setTrip(newTrip)
      newFrequency.setId(0)
      newFrequency
    }.toList

    (newTrip, newFrequencies)
  }

  final case class TripAndStopTimes(trip: Trip, stopTimes: Seq[StopTime])

  /**
    * @param startTime start time in milliseconds
    * @param endTime   end time in milliseconds
    */
  final case class TimeFrame(startTime: Int, endTime: Int)
  final object TimeFrame {
    val WholeDay: TimeFrame = TimeFrame(0, 86400)
  }

  private final case class TripDiffAcc(
    trip: TripAndStopTimes,
    arrivalDiffs: List[Int],
    departureDiffs: List[Int],
    stops: List[Stop]
  )
  private final case class HandledRepeatingAcc(
    handledDiffs: List[TripDiffAcc],
    repeatingTrips: TrieMap[String, Seq[(TripAndStopTimes, Int)]]
  )

  // additional classes for 'onebusaway' strategies
  final class IntValueSetter(replacementValue: Integer) extends ValueSetter {
    override def setValue(bean: BeanWrapper, propertyName: String): Unit =
      if (replacementValue != null) bean.setPropertyValue(propertyName, replacementValue)
  }

  final class StopTimeMatch(stopTime: StopTime) extends EntityMatch {
    override def isApplicableToObject(obj: Any): Boolean = obj match {
      case testStopTime: StopTime => testStopTime.getId == stopTime.getId
      case _                      => false
    }
  }

  final class StopTimeUpdateStrategy(arrivalTime: Int, departureTime: Int) extends EntityTransformStrategy {
    override def run(context: TransformContext, dao: GtfsMutableRelationalDao, entity: Any): Unit = entity match {
      case stopTime: StopTime =>
        stopTime.setArrivalTime(arrivalTime)
        stopTime.setDepartureTime(departureTime)
      case _ => ()
    }
  }

  final class FilterServiceIdStrategy(serviceIdFilter: String) extends GtfsTransformStrategy {
    override def run(context: TransformContext, dao: GtfsMutableRelationalDao): Unit = {
      for (serviceId <- dao.getAllServiceIds.asScala if serviceId.getId != serviceIdFilter) {
        for (trip <- dao.getTripsForServiceId(serviceId).asScala) {
          for (stopTime <- dao.getStopTimesForTrip(trip).asScala) {
            dao.removeEntity[Integer, StopTime](stopTime)
          }
          dao.removeEntity[AgencyAndId, Trip](trip)
        }
        UpdateLibrary.clearDaoCache(dao)
      }
    }
  }
}
