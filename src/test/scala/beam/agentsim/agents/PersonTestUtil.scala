package beam.agentsim.agents

import beam.router.Modes.BeamMode
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import org.matsim.api.core.v01.population.Person

object PersonTestUtil {

  def putDefaultBeamAttributes(person: Person, availableModes: Seq[BeamMode]): Unit = {
    person.getCustomAttributes.put(
      "beam-attributes",
      AttributesOfIndividual(
        HouseholdAttributes.EMPTY,
        None,
        false,
        availableModes,
        15.0,
        None,
        None
      )
    )
    person.getCustomAttributes.put("rank", 1.asInstanceOf[Object])
  }

}
