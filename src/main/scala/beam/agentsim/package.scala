package beam

import beam.agentsim.agents.PersonAgent
import beam.agentsim.core.Modes.BeamMode
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.opentripplanner.routing.core.TraverseMode

/**
  * Created by sfeygin on 1/27/17.
  */
package object agentsim {

  val SimName: String = "sf-bay"

  implicit def personId2PersonAgentId(id: Id[Person]): Id[PersonAgent] = Id.create(id, classOf[PersonAgent])

  implicit def personAgentId2PersonId(id: Id[PersonAgent]): Id[Person] = Id.createPersonId(id)



}
