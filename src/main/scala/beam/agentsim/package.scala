package beam

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.rideHail.RideHailingAgent
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

/**
  * Created by sfeygin on 1/27/17.
  */
package object agentsim {

  implicit def personId2PersonAgentId(id: Id[Person]): Id[PersonAgent] = Id.create(id, classOf[PersonAgent])

  implicit def personAgentId2PersonId(id: Id[PersonAgent]): Id[Person] = Id.createPersonId(id)

  implicit def vehicleId2BeamVehicleId(id: Id[Vehicle]): Id[BeamVehicle] = Id.create(id, classOf[BeamVehicle])

  implicit def beamVehicleId2VehicleId(id: Id[BeamVehicle]): Id[Vehicle] = Id.createVehicleId(id)

  implicit def beamVehicleMaptoMatsimVehicleMap(beamVehicleMap:Map[Id[BeamVehicle],BeamVehicle]):Map[Id[Vehicle],Vehicle]={
    beamVehicleMap.map({ case (vid, veh) => (Id.createVehicleId(vid), veh.matSimVehicle) })
  }

  implicit def personId2RideHailAgentId(id: Id[Person]): Id[RideHailingAgent] = {
    Id.create(s"${RideHailingAgent.idPrefix}${prefixStrip(id)}", classOf[RideHailingAgent])
  }

  def prefixStrip(id: Id[_]): String = {
    id.toString.replaceFirst("(?!=-)[a-zA-Z]+", "")
  }
}
