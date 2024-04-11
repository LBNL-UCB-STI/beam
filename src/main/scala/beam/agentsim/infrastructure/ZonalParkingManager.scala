package beam.agentsim.infrastructure

import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import org.locationtech.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.util.Random

class ZonalParkingManager(parkingZones: Map[Id[ParkingZoneId], ParkingZone]) extends ParkingNetwork(parkingZones) {

  override protected val searchFunctions: Option[InfrastructureFunctions] = None
}

object ZonalParkingManager extends LazyLogging {

  // this number should be less than the MaxSearchRadius config value, tuned to being
  // slightly less than the average distance between TAZ centroids.

  val AveragePersonWalkingSpeed: Double = 1.4 // in m/s
  val HourInSeconds: Int = 3600

  /**
    * constructs a ZonalParkingManager with provided parkingZones
    *
    * @return an instance of the ZonalParkingManager class
    */
  def apply(
    parkingZones: Map[Id[ParkingZoneId], ParkingZone],
    tazTreeMap: TAZTreeMap,
    distanceFunction: (Coord, Coord) => Double,
    boundingBox: Envelope,
    minSearchRadius: Double,
    maxSearchRadius: Double,
    fractionOfSameTypeZones: Double,
    minNumberOfSameTypeZones: Int,
    seed: Int,
    mnlParkingConfig: BeamConfig.Beam.Agentsim.Agents.Parking.MultinomialLogit,
    estimatedMinParkingDurationInSeconds: Double
  ): ZonalParkingManager = {
    new ZonalParkingManager(parkingZones) {
      if (maxSearchRadius < minSearchRadius) {
        logger.warn(
          s"maxSearchRadius of $maxSearchRadius meters provided from config is less than the fixed minimum search radius of $minSearchRadius; no searches will occur with these settings."
        )
      }
      override val searchFunctions: Option[InfrastructureFunctions] = Some(
        new ParkingFunctions(
          tazTreeMap,
          parkingZones,
          distanceFunction,
          minSearchRadius,
          maxSearchRadius,
          0.0,
          estimatedMinParkingDurationInSeconds,
          0.0,
          fractionOfSameTypeZones,
          minNumberOfSameTypeZones,
          boundingBox,
          seed,
          mnlParkingConfig
        )
      )
    }
  }

  /**
    * constructs a ZonalParkingManager with provided parkingZones
    *
    * @return an instance of the ZonalParkingManager class
    */
  def apply(
    parkingZones: Map[Id[ParkingZoneId], ParkingZone],
    TAZTreeMap: TAZTreeMap,
    envelopeInUTM: Envelope,
    beamConfig: BeamConfig,
    distanceFunction: (Coord, Coord) => Double
  ): ZonalParkingManager = {
    ZonalParkingManager(
      parkingZones,
      TAZTreeMap,
      distanceFunction,
      envelopeInUTM,
      beamConfig.beam.agentsim.agents.parking.minSearchRadius,
      beamConfig.beam.agentsim.agents.parking.maxSearchRadius,
      beamConfig.beam.agentsim.agents.parking.fractionOfSameTypeZones,
      beamConfig.beam.agentsim.agents.parking.minNumberOfSameTypeZones,
      beamConfig.matsim.modules.global.randomSeed,
      beamConfig.beam.agentsim.agents.parking.multinomialLogit,
      beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds
    )
  }

  /**
    * constructs a ZonalParkingManager from a string iterator (typically, for testing)
    *
    * @param parkingDescription line-by-line string representation of parking including header
    * @return
    */
  def apply(
    parkingDescription: Iterator[String],
    TAZTreeMap: TAZTreeMap,
    boundingBox: Envelope,
    distanceFunction: (Coord, Coord) => Double,
    minSearchRadius: Double,
    maxSearchRadius: Double,
    seed: Int,
    mnlParkingConfig: BeamConfig.Beam.Agentsim.Agents.Parking.MultinomialLogit,
    beamConfig: BeamConfig,
    beamServicesMaybe: Option[BeamServices]
  ): ZonalParkingManager = {
    val parking = ParkingZoneFileUtils.fromIterator(
      parkingDescription,
      Some(beamConfig),
      beamServicesMaybe,
      new Random(seed)
    )
    ZonalParkingManager(
      parking.zones.filter(_._2.chargingPointType.isEmpty).toMap,
      TAZTreeMap,
      distanceFunction,
      boundingBox,
      minSearchRadius,
      maxSearchRadius,
      0.5,
      10,
      seed,
      mnlParkingConfig,
      beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds
    )
  }

  /**
    * constructs a ZonalParkingManager with provided parkingZones
    *
    * @return an instance of the ZonalParkingManager class
    */
  def init(
    parkingZones: Map[Id[ParkingZoneId], ParkingZone],
    envelopeInUTM: Envelope,
    beamServices: BeamServices
  ): ZonalParkingManager = {
    ZonalParkingManager(
      parkingZones,
      beamServices.beamScenario.tazTreeMap,
      envelopeInUTM,
      beamServices.beamConfig,
      beamServices.geo.distUTMInMeters
    )
  }

}
