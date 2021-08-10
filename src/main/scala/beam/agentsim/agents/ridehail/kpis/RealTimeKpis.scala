package beam.agentsim.agents.ridehail.kpis

import beam.agentsim.agents.ridehail.kpis.KpiRegistry.Kpi
import beam.agentsim.agents.ridehail.kpis.RideHailKpisObject._
import beam.agentsim.agents.ridehail.kpis.TemporalAggregation.{HalfHourly, Hourly, QuarterHourly}
import beam.agentsim.infrastructure.taz.H3TAZ.HexIndex
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.event.TAZSkimmerEvent
import beam.sim.BeamServices
import enumeratum.{Enum, EnumEntry}
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.mutable

/**
  * Class for storing and retrieving spatial and temporal key performance indicators (kpis) with indexing based on aggregations.
  *
  * Key behaviors:
  * -- Stores Double-valued metrics.
  * -- Requires a time associated with each KPI.
  * -- Optionally a location can also be associated with each KPI.
  * -- Automatically indexes observations to 15-minute, 30-minute, and 60-minute time periods... allowing very efficient
  *    access to stored values associated with any time period, including non-aggregated (i.e. indexed to the 1-second time period)
  * -- If location provided, automatically indexes location to H3 HEX as well as TAZ for fast retrieval.
  * -- Returns a Vector of observations on retrieval to ensure all mutability is kept private.
  *
  * WARNINGS:
  * -- Not thread-safe, this should only be used within a single Actor, e.g. within the RideHailManager. Do not share across Actors!
  * -- This is intended for collecting AND USING data within an iteration... notably different from how Skimmers function, which is only
  *    to collect data during an iteration for use in subsequent iterations. Future improvements on this class could be made to
  *    better synchronize or complement skimmers... e.g. if this class doesn't have data about a time period or spatial index, then
  *    a skimmer could be used as a fallback.
  */
class RealTimeKpis(beamServices: BeamServices, observationSampleRateInSeconds: Int = 1) {

  private val kpis: mutable.HashMap[SpatioTemporalKey, mutable.ListBuffer[Double]] =
    mutable.HashMap.empty[SpatioTemporalKey, mutable.ListBuffer[Double]]

  /**
    * Store an observation of a KPI with at a particular time.
    *
    * @param value observation to be stored
    * @param kpi kpi characterizing the observation
    * @param time time in seconds
    */
  def storeObservation(value: Double, kpi: Kpi, time: Int): Unit = {
    storeObservation(value, kpi, time, None)
  }

  /**
    * Store an observation of a KPI with at a particular time and location
    *
    * @param value observation to be stored
    * @param kpi kpi characterizing the observation
    * @param time time in seconds
    * @param location location as a Coord
    */
  def storeObservation(value: Double, kpi: Kpi, time: Int, location: Coord): Unit = {
    storeObservation(value, kpi, time, Some(location))
  }

  /**
    * Convenience function that converts a TAZSkimmerEvent to storage of the appropriate KPI and value internally. There is
    * potentially duplication of functionality between Skimmers and this class, this method facilitates keeping the structure of these
    * collectors consistent.
    *
    * @param event
    */
  def storeObservation(event: TAZSkimmerEvent): Unit = {
    val kpi = beamServices.beamCustomizationAPI.getRidehailManagerCustomizationAPI.getKpiRegistry
      .withName(event.key)
    storeObservation(event.value, kpi, event.time, event.coord)
  }

  /**
    * Internal method to fully characterize how to store an observation... with location as an Option.
    *
    * @param value
    * @param kpi
    * @param time
    * @param locationOpt
    */
  private def storeObservation(value: Double, kpi: Kpi, time: Int, locationOpt: Option[Coord]) = {
    val (quarterHour, halfHour, hour) = timeInSecondsToBins(time)
    val (hexIndex, tazId) = locationOpt match {
      case Some(location) =>
        val hexIndex = beamServices.beamScenario.h3taz.getIndex(location)
        val idTaz = beamServices.beamScenario.tazTreeMap.getTAZ(location.getX, location.getY).tazId
        (Some(hexIndex), Some(idTaz))
      case None =>
        (None, None)
    }
    putObservation(IndexedSpatioTemporalKpi(kpi, time, quarterHour, halfHour, hour, hexIndex, tazId), value)
  }

  /**
    * Retrieve all observed kpis from the TAZ that matches the location argument for the given time and TemporalAggregation.
    *
    * The temporal aggregation indicates what temporal grouping of observations are of interest: None means no-aggregation,
    * Hourly means all observations within the same hour as the time argument, and so on.
    *
    * @param kpi the KPI of interest to retrieve
    * @param time the time in seconds of interest
    * @param location the location of interest
    * @param temporalAggregation the temporal aggregation of the observations
    * @return
    */
  def getObservationsFromSameTazUsingLocation(
    kpi: Kpi,
    time: Int,
    location: Coord,
    temporalAggregation: Option[TemporalAggregation] = None
  ) = {
    val hexIndex = beamServices.beamScenario.h3taz.getIndex(location)
    getObservations(kpi, time, temporalAggregation, None, Some(beamServices.beamScenario.h3taz.getTAZ(hexIndex)))
  }

  /**
    * Retrieve all observation from the HEX that matches the location argument for the given time and TemporalAggregation.
    *
    * The temporal aggregation indicates what temporal grouping of observations are of interest: None means no-aggregation,
    * Hourly means all observations within the same hour as the time argument, and so on.
    *
    * @param kpi the KPI of interest to retrieve
    * @param time the time in seconds of interest
    * @param location the location of interest
    * @param temporalAggregation the temporal aggregation of the observations
    * @return
    */
  def getObservationsFromSameHexUsingLocation(
    kpi: Kpi,
    time: Int,
    location: Coord,
    temporalAggregation: Option[TemporalAggregation] = None
  ) = {
    getObservations(kpi, time, temporalAggregation, Some(beamServices.beamScenario.h3taz.getIndex(location)), None)
  }

  /**
    * Retrieve all observed kpis for the given time and TemporalAggregation as well as the optional spatial keys specified.
    *
    * The temporal aggregation indicates what temporal grouping of observations are of interest: None means no-aggregation,
    * Hourly means all observations within the same hour as the time argument, and so on.
    *
    * WARNING: only one spatial key can be defined at a time.
    *
    * @param kpi the KPI of interest to retrieve
    * @param time the time in seconds of interest
    * @param temporalAggregation the temporal aggregation of the observations
    * @param hexKey the optional H3 HEX to use for spatial aggregation
    * @param tazKey the optional TAZ to use for spatial aggregation
    * @return
    */
  def getObservations(
    kpi: Kpi,
    time: Int,
    temporalAggregation: Option[TemporalAggregation] = None,
    hexKey: Option[HexIndex] = None,
    tazKey: Option[Id[TAZ]] = None
  ) = {
    assert(hexKey.isEmpty || tazKey.isEmpty)
    val (quarterHour, halfHour, hour) = timeInSecondsToBins(time)
    val (timeKey, quarterHourKey, halfHourKey, hourKey) = temporalAggregation match {
      case None =>
        (Some(time), Some(quarterHour), Some(halfHour), Some(hour))
      case Some(QuarterHourly) =>
        (None, Some(quarterHour), Some(halfHour), Some(hour))
      case Some(HalfHourly) =>
        (None, None, Some(halfHour), Some(hour))
      case Some(Hourly) =>
        (None, None, None, Some(hour))
    }
    val theKey = SpatioTemporalKey(kpi, timeKey, quarterHourKey, halfHourKey, hourKey, hexKey, tazKey)
    val theResult = kpis.get(theKey).map(_.toVector).getOrElse(Vector())
    theResult
  }

  /**
    * Retrieve all observed kpis for the most recent time and TemporalAggregation for which data is available.
    *
    * The temporal aggregation indicates what temporal grouping of observations are of interest: None means no-aggregation,
    * [[Hourly]] means aggregated over all observations within the same hour as the time argument, and so on.
    *
    * WARNING: only one spatial key can be defined at a time.
    *
    * @param kpi the KPI of interest to retrieve
    * @param time the time in seconds of interest
    * @param temporalAggregation the temporal aggregation of the observations
    * @param hexKey the optional H3 HEX to use for spatial aggregation
    * @param tazKey the optional TAZ to use for spatial aggregation
    * @return
    */
  def getLatestObservations(
    kpi: Kpi,
    time: Int,
    temporalAggregation: Option[TemporalAggregation] = None,
    hexKey: Option[HexIndex] = None,
    tazKey: Option[Id[TAZ]] = None
  ) = {
    val latestTimeOnASampleRate =
      (time.toDouble / observationSampleRateInSeconds.toDouble).toInt * observationSampleRateInSeconds
    Iterator
      .iterate((toTimeOrBin(latestTimeOnASampleRate, temporalAggregation), Vector[Double]())) {
        case (currentTimeOrBin, _) =>
          (
            currentTimeOrBin - observationSampleRateInSeconds,
            getObservations(
              kpi,
              toTimeInSeconds(currentTimeOrBin, temporalAggregation),
              temporalAggregation,
              hexKey,
              tazKey
            )
          )
      }
      .find(timeAndResult => timeAndResult._2.size > 0 || timeAndResult._1 < 0)
      .map(_._2)
      .getOrElse(Vector())
  }

  /**
    * Internal method to store the observation using all appropriate indexes.
    *
    * @param observation instance of [[IndexedSpatioTemporalKpi]]
    * @param value the double value of the observation to store
    */
  private def putObservation(observation: IndexedSpatioTemporalKpi, value: Double): Unit = {
    putKeyValue(observation.toKeyOnTime, value)
    putKeyValue(observation.toKeyOnQuarterHour, value)
    putKeyValue(observation.toKeyOnHalfHour, value)
    putKeyValue(observation.toKeyOnHour, value)
    if (observation.hexIndex.isDefined) {
      putKeyValue(observation.toKeyOnTimeAndHex, value)
      putKeyValue(observation.toKeyOnQuarterHourAndHex, value)
      putKeyValue(observation.toKeyOnHalfHourAndHex, value)
      putKeyValue(observation.toKeyOnHourAndHex, value)
    }
    if (observation.idTaz.isDefined) {
      putKeyValue(observation.toKeyOnTimeAndTaz, value)
      putKeyValue(observation.toKeyOnQuarterHourAndTaz, value)
      putKeyValue(observation.toKeyOnHalfHourAndTaz, value)
      putKeyValue(observation.toKeyOnHourAndTaz, value)
    }
  }

  /**
    * Adds the value so the kpis HashMap using the key.
    *
    * @param key the key to use when adding to the HashMap
    * @param value the value of the kpi
    */
  private def putKeyValue(key: SpatioTemporalKey, value: Double): Unit = {
    kpis.get(key) match {
      case Some(listBuffer) =>
        listBuffer += value
      case None =>
        kpis += (key -> mutable.ListBuffer(value))
    }
  }

  /**
    * Helper method to covert a time in seconds to the appropriate 15-minute, 30-minute, and 60-minute bin.
    *
    * @param time the time in seconds
    * @return a 3-tuple with the 15-minute, 30-minute, and 60-minute bins
    */
  private def timeInSecondsToBins(time: Int): (QuarterHourBin, HalfHourBin, HourBin) =
    (
      (time.toDouble / (60.0 * 15.0)).toInt,
      (time.toDouble / (60.0 * 30.0)).toInt,
      (time.toDouble / (60.0 * 60.0)).toInt
    )

  /**
    * Helper method to convert a give time or bin (i.e. it could be a time in seconds or a bin number) to a
    * corresponding time in seconds. For bins, this will always be the time in seconds at the beginning of the
    * time interval. I.e. for Hourly and timeOrBin == 1, a value of 3600 will be returned.
    *
    * @param timeOrBin a time in seconds or time bin
    * @param temporalAggregation the temporal aggregation of the timeOrBin argument
    * @return
    */
  private def toTimeInSeconds(timeOrBin: Int, temporalAggregation: Option[TemporalAggregation]): Int = {
    temporalAggregation match {
      case None =>
        timeOrBin
      case Some(QuarterHourly) =>
        timeOrBin * 60 * 15
      case Some(HalfHourly) =>
        timeOrBin * 60 * 30
      case Some(Hourly) =>
        timeOrBin * 60 * 60
    }
  }

  /**
    * Helper method to convert a time in seconds to a time bin number. If temporalAggregation is None, then the value
    * of timeInSeconds is returned.
    *
    * @param timeInSeconds a time in seconds
    * @param temporalAggregation the temporal aggregation of the needed result
    * @return
    */
  private def toTimeOrBin(timeInSeconds: Int, temporalAggregation: Option[TemporalAggregation]): Int = {
    temporalAggregation match {
      case None =>
        timeInSeconds
      case Some(QuarterHourly) =>
        timeInSecondsToBins(timeInSeconds)._1
      case Some(HalfHourly) =>
        timeInSecondsToBins(timeInSeconds)._2
      case Some(Hourly) =>
        timeInSecondsToBins(timeInSeconds)._3
    }
  }
}

object RideHailKpisObject {
  type QuarterHourBin = Int
  type HalfHourBin = Int
  type HourBin = Int
  case class SpatialKeys(hexIndex: HexIndex, taz: Id[TAZ])

  case class SpatioTemporalKey(
    kpi: Kpi,
    time: Option[Int],
    quarterHour: Option[QuarterHourBin],
    halfHour: Option[HalfHourBin],
    hour: Option[HourBin],
    hexIndex: Option[HexIndex],
    idTaz: Option[Id[TAZ]]
  )

  /**
    * An IndexedSpatioTemporalKpi contains all of the fields necessary to create a [[SpatioTemporalKey]] for any needed
    * level of temporal or spatial aggregation.
    *
    * @param kpi
    * @param time
    * @param quarterHour
    * @param halfHour
    * @param hour
    * @param hexIndex
    * @param idTaz
    */
  case class IndexedSpatioTemporalKpi(
    kpi: Kpi,
    time: Int,
    quarterHour: QuarterHourBin,
    halfHour: HalfHourBin,
    hour: HourBin,
    hexIndex: Option[HexIndex],
    idTaz: Option[Id[TAZ]]
  ) {

    def toKeyOnTime =
      SpatioTemporalKey(
        this.kpi,
        Some(this.time),
        Some(this.quarterHour),
        Some(this.halfHour),
        Some(this.hour),
        None,
        None
      )

    def toKeyOnQuarterHour =
      SpatioTemporalKey(this.kpi, None, Some(this.quarterHour), Some(this.halfHour), Some(this.hour), None, None)
    def toKeyOnHalfHour = SpatioTemporalKey(this.kpi, None, None, Some(this.halfHour), Some(this.hour), None, None)
    def toKeyOnHour = SpatioTemporalKey(this.kpi, None, None, None, Some(this.hour), None, None)

    def toKeyOnTimeAndHex =
      SpatioTemporalKey(
        this.kpi,
        Some(this.time),
        Some(this.quarterHour),
        Some(this.halfHour),
        Some(this.hour),
        this.hexIndex,
        None
      )

    def toKeyOnTimeAndTaz =
      SpatioTemporalKey(
        this.kpi,
        Some(this.time),
        Some(this.quarterHour),
        Some(this.halfHour),
        Some(this.hour),
        None,
        this.idTaz
      )

    def toKeyOnQuarterHourAndHex =
      SpatioTemporalKey(
        this.kpi,
        None,
        Some(this.quarterHour),
        Some(this.halfHour),
        Some(this.hour),
        this.hexIndex,
        None
      )

    def toKeyOnQuarterHourAndTaz =
      SpatioTemporalKey(this.kpi, None, Some(this.quarterHour), Some(this.halfHour), Some(this.hour), None, this.idTaz)

    def toKeyOnHalfHourAndHex =
      SpatioTemporalKey(this.kpi, None, None, Some(this.halfHour), Some(this.hour), this.hexIndex, None)

    def toKeyOnHalfHourAndTaz =
      SpatioTemporalKey(this.kpi, None, None, Some(this.halfHour), Some(this.hour), None, this.idTaz)
    def toKeyOnHourAndHex = SpatioTemporalKey(this.kpi, None, None, None, Some(this.hour), this.hexIndex, None)
    def toKeyOnHourAndTaz = SpatioTemporalKey(this.kpi, None, None, None, Some(this.hour), None, this.idTaz)
  }

}

sealed trait TemporalAggregation extends EnumEntry

object TemporalAggregation extends Enum[TemporalAggregation] {
  val values = findValues
  case object QuarterHourly extends TemporalAggregation
  case object HalfHourly extends TemporalAggregation
  case object Hourly extends TemporalAggregation
}

sealed trait SpatialAggregation extends EnumEntry

object SpatialAggregation extends Enum[SpatialAggregation] {
  val values = findValues
  case object Hex extends SpatialAggregation
  case object Taz extends SpatialAggregation
}
