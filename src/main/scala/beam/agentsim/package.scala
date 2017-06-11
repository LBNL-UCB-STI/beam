package beam

import beam.agentsim.agents.PersonAgent
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

/**
  * Created by sfeygin on 1/27/17.
  */
package object agentsim {

  implicit def personId2PersonAgentId(id: Id[Person]): Id[PersonAgent] = Id.create(id, classOf[PersonAgent])

  implicit def personAgentId2PersonId(id: Id[PersonAgent]): Id[Person] = Id.createPersonId(id)

}
