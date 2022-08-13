package beam.agentsim.infrastructure

import beam.agentsim.infrastructure.parking.{ParkingZone, ParkingZoneId}
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.BeamServices
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.collections.QuadTree

class RidehailDepotNetwork(override val parkingZones: Map[Id[ParkingZoneId], ParkingZone])
    extends ChargingNetwork(parkingZones) {

  override protected val searchFunctions: Option[InfrastructureFunctions] = None

}

object RidehailDepotNetwork {

  // a ride hail agent is searching for a charging depot and is not in service of an activity.
  // for this reason, a higher max radius is reasonable.
  val SearchStartRadius: Double = 40000.0 // meters
  val SearchMaxRadius: Int = 80465 // 50 miles, in meters
  val FractionOfSameTypeZones: Double = 0.2 // 20%
  val MinNumberOfSameTypeZones: Int = 5

  def apply(
    parkingZones: Map[Id[ParkingZoneId], ParkingZone],
    geoQuadTree: QuadTree[TAZ],
    idToGeoMapping: scala.collection.Map[Id[TAZ], TAZ],
    boundingBox: Envelope,
    beamServices: BeamServices
  ): RidehailDepotNetwork = {
    new RidehailDepotNetwork(parkingZones) {
      override val searchFunctions: Option[InfrastructureFunctions] = Some(
        new DefaultRidehailFunctions(
          geoQuadTree,
          idToGeoMapping,
          parkingZones,
          // parkingZoneIdToParkingZoneDepotData,
          beamServices.geo.distUTMInMeters,
          SearchStartRadius,
          SearchMaxRadius,
          FractionOfSameTypeZones,
          MinNumberOfSameTypeZones,
          boundingBox,
          beamServices.beamConfig.matsim.modules.global.randomSeed,
          beamServices.beamScenario.fuelTypePrices,
          beamServices.beamConfig.beam.agentsim.agents.rideHail,
          beamServices.skims,
          beamServices.beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds
        )
      )
    }
  }

  def init(
    parkingZones: Map[Id[ParkingZoneId], ParkingZone],
    boundingBox: Envelope,
    beamServices: BeamServices
  ): RidehailDepotNetwork = {
    RidehailDepotNetwork(
      parkingZones,
      beamServices.beamScenario.tazTreeMap.tazQuadTree,
      beamServices.beamScenario.tazTreeMap.idToTAZMapping,
      boundingBox,
      beamServices
    )
  }
}
