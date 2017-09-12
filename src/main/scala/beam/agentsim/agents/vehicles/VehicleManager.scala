package beam.agentsim.agents.vehicles

import akka.actor.Actor
import beam.agentsim.ResourceManager
import org.matsim.vehicles.Vehicle

/**
  * @author dserdiuk
  */
trait VehicleManager[T<:Vehicle] extends Actor with ResourceManager[T]


