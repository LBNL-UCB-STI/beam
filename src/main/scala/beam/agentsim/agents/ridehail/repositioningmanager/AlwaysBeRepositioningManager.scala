package beam.agentsim.agents.ridehail.repositioningmanager

import beam.agentsim.agents.ridehail.RideHailManager
import beam.agentsim.agents.ridehail.RideHailManagerHelper.RideHailAgentLocation
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.router.BeamRouter.Location
import beam.sim.BeamServices
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id

/**
  * A repositioning manager that ensures all idle vehicles keep moving at every opportunity. Useful for testing and
  * debugging.
  *
  * @param beamServices
  * @param rideHailManager
  */
class AlwaysBeRepositioningManager(val beamServices: BeamServices, val rideHailManager: RideHailManager)
    extends RepositioningManager(beamServices, rideHailManager)
    with LazyLogging {

  def repositionVehicles(
    idleVehicles: scala.collection.Map[Id[BeamVehicle], RideHailAgentLocation],
    tick: Int
  ): Vector[(Id[BeamVehicle], Location)] = {
    idleVehicles.map { idleVehicle =>
      val tazSomeDistanceAway = beamServices.beamScenario.tazTreeMap.getTAZs.find { taz =>
        beamServices.geo.distUTMInMeters(taz.coord, idleVehicle._2.getCurrentLocationUTM(tick, beamServices)) > 1000
      }
      (idleVehicle._1, tazSomeDistanceAway.get.coord)
    }.toVector
  }
}
