package beam.agentsim.infrastructure

import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ZoneSearchTree
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.util.Random

class ZonalParkingManager[GEO: GeoLevel](parkingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]])
    extends ParkingNetwork[GEO](parkingZones) {

  protected val parkingZoneTree: ZoneSearchTree[GEO] =
    ParkingZoneFileUtils.createZoneSearchTree(parkingZones.values.toSeq)

  override protected val searchFunctions: Option[InfrastructureFunctions[_]] = None
}

object ZonalParkingManager extends LazyLogging {

  // this number should be less than the MaxSearchRadius config value, tuned to being
  // slightly less than the average distance between TAZ centroids.

  val AveragePersonWalkingSpeed: Double = 1.4 // in m/s
  val HourInSeconds: Int = 3600
  val DollarsInCents: Double = 100.0

  /**
    * constructs a ZonalParkingManager with provided parkingZones
    *
    * @return an instance of the ZonalParkingManager class
    */
  def apply[GEO: GeoLevel](
    parkingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]],
    geoQuadTree: QuadTree[GEO],
    idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
    geoToTAZ: GEO => TAZ,
    distanceFunction: (Coord, Coord) => Double,
    boundingBox: Envelope,
    minSearchRadius: Double,
    maxSearchRadius: Double,
    seed: Int,
    mnlParkingConfig: BeamConfig.Beam.Agentsim.Agents.Parking.MulitnomialLogit
  ): ZonalParkingManager[GEO] = {
    new ZonalParkingManager(parkingZones) {
      if (maxSearchRadius < minSearchRadius) {
        logger.warn(
          s"maxSearchRadius of $maxSearchRadius meters provided from config is less than the fixed minimum search radius of $minSearchRadius; no searches will occur with these settings."
        )
      }
      override val searchFunctions: Option[InfrastructureFunctions[_]] = Some(
        new ParkingFunctions(
          geoQuadTree,
          idToGeoMapping,
          geoToTAZ,
          parkingZones,
          distanceFunction,
          minSearchRadius,
          maxSearchRadius,
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
  def apply[GEO: GeoLevel](
    parkingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]],
    geoQuadTree: QuadTree[GEO],
    idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
    geoToTAZ: GEO => TAZ,
    envelopeInUTM: Envelope,
    beamConfig: BeamConfig,
    distanceFunction: (Coord, Coord) => Double
  ): ZonalParkingManager[GEO] = {
    ZonalParkingManager[GEO](
      parkingZones,
      geoQuadTree,
      idToGeoMapping,
      geoToTAZ,
      distanceFunction,
      envelopeInUTM,
      beamConfig.beam.agentsim.agents.parking.minSearchRadius,
      beamConfig.beam.agentsim.agents.parking.maxSearchRadius,
      beamConfig.matsim.modules.global.randomSeed,
      beamConfig.beam.agentsim.agents.parking.mulitnomialLogit
    )
  }

  /**
    * constructs a ZonalParkingManager from a string iterator (typically, for testing)
    *
    * @param parkingDescription line-by-line string representation of parking including header
    * @param random             random generator used for sampling parking locations
    * @param includesHeader     true if the parkingDescription includes a csv-style header
    * @return
    */
  def apply[GEO: GeoLevel](
    parkingDescription: Iterator[String],
    geoQuadTree: QuadTree[GEO],
    idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
    geoToTAZ: GEO => TAZ,
    boundingBox: Envelope,
    distanceFunction: (Coord, Coord) => Double,
    minSearchRadius: Double,
    maxSearchRadius: Double,
    seed: Int,
    mnlParkingConfig: BeamConfig.Beam.Agentsim.Agents.Parking.MulitnomialLogit,
    beamConfig: BeamConfig,
    includesHeader: Boolean = true
  ): ZonalParkingManager[GEO] = {
    val parking = ParkingZoneFileUtils.fromIterator(
      parkingDescription,
      Some(beamConfig),
      new Random(seed)
    )
    ZonalParkingManager[GEO](
      parking.zones.filter(_._2.chargingPointType.isEmpty).toMap,
      geoQuadTree,
      idToGeoMapping,
      geoToTAZ,
      distanceFunction,
      boundingBox,
      minSearchRadius,
      maxSearchRadius,
      seed,
      mnlParkingConfig
    )
  }

  /**
    * constructs a ZonalParkingManager with provided parkingZones
    *
    * @return an instance of the ZonalParkingManager class
    */
  def init(
    parkingZones: Map[Id[ParkingZoneId], ParkingZone[TAZ]],
    envelopeInUTM: Envelope,
    beamServices: BeamServices
  ): ZonalParkingManager[TAZ] = {
    ZonalParkingManager[TAZ](
      parkingZones,
      beamServices.beamScenario.tazTreeMap.tazQuadTree,
      beamServices.beamScenario.tazTreeMap.idToTAZMapping,
      identity[TAZ](_),
      envelopeInUTM,
      beamServices.beamConfig,
      beamServices.geo.distUTMInMeters(_, _)
    )
  }

  /**
    * constructs a ZonalParkingManager with provided parkingZones
    *
    * @return an instance of the ZonalParkingManager class
    */
  def init(
    parkingZones: Map[Id[ParkingZoneId], ParkingZone[Link]],
    geoQuadTree: QuadTree[Link],
    idToGeoMapping: scala.collection.Map[Id[Link], Link],
    geoToTAZ: Link => TAZ,
    envelopeInUTM: Envelope,
    beamServices: BeamServices
  ): ZonalParkingManager[Link] = {
    ZonalParkingManager[Link](
      parkingZones,
      geoQuadTree,
      idToGeoMapping,
      geoToTAZ,
      envelopeInUTM,
      beamServices.beamConfig,
      beamServices.geo.distUTMInMeters(_, _)
    )
  }
}
