package beam.agentsim.agents.vehicles

import akka.actor.Actor
import beam.agentsim.ResourceManager
import org.matsim.vehicles.Vehicle

/**
  * @author dserdiuk
  */
trait VehicleManager extends Actor with ResourceManager[Vehicle]


