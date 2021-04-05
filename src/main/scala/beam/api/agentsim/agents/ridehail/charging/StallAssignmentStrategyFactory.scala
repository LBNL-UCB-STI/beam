package beam.api.agentsim.agents.ridehail.charging

import beam.agentsim.agents.ridehail.RideHailDepotParkingManager
import beam.agentsim.agents.ridehail.charging.DefaultStallAssignmentStrategyRegistry.SeparateParkingZoneStrategy
import beam.agentsim.agents.ridehail.charging.{
  DefaultStallAssignmentStrategyRegistry,
  NoChargingManager,
  SeparateParkingZoneStallAssignmentStrategy,
  StallAssignmentStrategy,
  StallAssignmentStrategyRegistry,
  VehicleChargingManager,
  VehicleChargingManagerRegistry
}
import beam.agentsim.infrastructure.parking.GeoLevel

import scala.util.Try

/**
  * API defining [[StallAssignmentStrategyFactory]] and its default implementation ([[DefaultStallAssignmentStrategyFactory]]), which
  * allows to instantiate from a variety of stall assignment strategies ([[StallAssignmentStrategy]]) currently available in BEAM.
  * In order to add new custom stall assignment strategies, a new [[StallAssignmentStrategyFactory]] can be implemented similar to [[DefaultStallAssignmentStrategyFactory]] and
  * [[DefaultStallAssignmentStrategyFactory]] can be used as a delegate to access the stall assignment strategies.
  */
trait StallAssignmentStrategyFactory {

  def create[GEO: GeoLevel](
    rideHailDepotParkingManager: RideHailDepotParkingManager[GEO],
    stallAssignmentStrategyName: String
  ): Try[StallAssignmentStrategy]

}

/**
  * Default implementation of [[StallAssignmentStrategyFactory]].
  */
class DefaultStallAssignmentStrategyFactory extends StallAssignmentStrategyFactory {
  override def create[GEO: GeoLevel](
    rideHailDepotParkingManager: RideHailDepotParkingManager[GEO],
    stallAssignmentStrategyName: String
  ): Try[StallAssignmentStrategy] = {
    Try {
      DefaultStallAssignmentStrategyRegistry.withName(
        stallAssignmentStrategyName
      ) match {
        case SeparateParkingZoneStrategy =>
          new SeparateParkingZoneStallAssignmentStrategy()
        case x =>
          throw new IllegalStateException(s"There is no implementation for `$x`")
      }
    }
  }
}
