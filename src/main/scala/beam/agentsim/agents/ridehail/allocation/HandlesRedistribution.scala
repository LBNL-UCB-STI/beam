package beam.agentsim.agents.ridehail.allocation

import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

trait HandlesRedistribution {

  def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)]

}
