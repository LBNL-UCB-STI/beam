package beam.agentsim.agents.rideHail

import beam.agentsim.agents.rideHail.RideHailingManager.{RideHailingAgentLocation, RideHailingInquiry}
import beam.agentsim.events.SpaceTime
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

trait RideHailRepositioningManager {

  def getVehiclesToReposition(tick: Double):Vector[(Id[Vehicle],SpaceTime)]

}
