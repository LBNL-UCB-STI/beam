package beam.agentsim.agents
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import beam.utils.plan.sampling.AvailableModeUtils
import org.matsim.api.core.v01.population.Person

object PersonTestUtil {

  def putDefaultBeamAttributes(person: Person) = {
    person.getCustomAttributes.put(
      "beam-attributes",
      AttributesOfIndividual(
        HouseholdAttributes.EMPTY,
        None,
        false,
        AvailableModeUtils.availableModeParser(
          "car,ride_hail,bike,bus,funicular,gondola,cable_car,ferry,tram,transit,rail,subway,tram"
        ),
        15.0,
        None,
        None
      )
    )
    person.getCustomAttributes.put("rank", 1.asInstanceOf[Object])
  }

}
