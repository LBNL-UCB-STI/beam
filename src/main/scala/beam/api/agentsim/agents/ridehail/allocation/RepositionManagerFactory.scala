package beam.api.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.ridehail.RideHailManager
import beam.agentsim.agents.ridehail.repositioningmanager._

import scala.util.Try

/**
  * API defining [[RepositionManagerFactory]] and its default implementation ([[DefaultRepositionManagerFactory]]), which
  * allows to instantiate from a variety of repositioning managers ([[RepositioningManager]]) currently available in BEAM. In order to
  * add new custom repositioning managers, a new [[RepositionManagerFactory]] can be implemented similar to [[DefaultRepositionManagerFactory]] and
  * [[DefaultRepositionManagerFactory]] can be used as a delegate to access the existing repositioning managers.
  */
trait RepositionManagerFactory {
  def create(rideHailManager: RideHailManager, repositioningManagerName: String): Try[RepositioningManager]
}

/**
  * Default implementation of [[RepositionManagerFactory]].
  */
class DefaultRepositionManagerFactory extends RepositionManagerFactory {

  override def create(rideHailManager: RideHailManager, repositioningManagerName: String): Try[RepositioningManager] = {

    Try {
      repositioningManagerName match {
        case "DEFAULT_REPOSITIONING_MANAGER" =>
          RepositioningManager[DefaultRepositioningManager](rideHailManager.beamServices, rideHailManager)
        case "DEMAND_FOLLOWING_REPOSITIONING_MANAGER" =>
          RepositioningManager[DemandFollowingRepositioningManager](rideHailManager.beamServices, rideHailManager)
        case "INVERSE_SQUARE_DISTANCE_REPOSITIONING_FACTOR" =>
          RepositioningManager[InverseSquareDistanceRepositioningFactor](
            rideHailManager.beamServices,
            rideHailManager
          )
        case "REPOSITIONING_LOW_WAITING_TIMES" =>
          RepositioningManager[RepositioningLowWaitingTimes](rideHailManager.beamServices, rideHailManager)
        case "THE_SAME_LOCATION_REPOSITIONING_MANAGER" =>
          RepositioningManager[TheSameLocationRepositioningManager](rideHailManager.beamServices, rideHailManager)
        case "ALWAYS_BE_REPOSITIONING_MANAGER" =>
          RepositioningManager[AlwaysBeRepositioningManager](rideHailManager.beamServices, rideHailManager)
        case x =>
          throw new IllegalStateException(s"There is no implementation for `$x`")
      }
    }
  }

}
