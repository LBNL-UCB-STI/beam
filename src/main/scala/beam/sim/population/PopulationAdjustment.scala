package beam.sim.population

import org.matsim.api.core.v01.population.Population
import org.matsim.utils.objectattributes.ObjectAttributes

trait PopulationAdjustment {
  def update(population: Population, personAttributes: ObjectAttributes): (Population, ObjectAttributes)
}

object PopulationAdjustment {
  def removeMode(availableModes: String, modeToRemove: String): String = {
    availableModes.split(",").filterNot(_.equalsIgnoreCase(modeToRemove)).mkString(",")
  }
}