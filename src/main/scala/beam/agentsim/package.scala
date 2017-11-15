package beam

import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.vehicles.BeamVehicle
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

/**
  * Created by sfeygin on 1/27/17.
  */
package object agentsim {

  implicit def personId2PersonAgentId(id: Id[Person]): Id[PersonAgent] = Id.create(id, classOf[PersonAgent])

  implicit def personAgentId2PersonId(id: Id[PersonAgent]): Id[Person] = Id.createPersonId(id)

  implicit def vehicleId2BeamVehicleId(id: Id[Vehicle]):Id[BeamVehicle] = Id.create(id,classOf[BeamVehicle])

  implicit def beamVehicleId2VehicleId(id: Id[BeamVehicle]):Id[Vehicle] = Id.createVehicleId(id)

}
