package beam.sim.population

import org.matsim.api.core.v01.population.Population
import org.matsim.utils.objectattributes.ObjectAttributes

class PercentagePopulationAdjustment extends PopulationAdjustment {
  override def update(population: Population, personAttributes: ObjectAttributes): (Population, ObjectAttributes) = {

    //removeMode(personAttributes,"car");

    //assignModeUniformDistribution("car",0.5,personAttributes,population);

    (population, personAttributes)
  }
}
// Method 1: remove mode from all attributes -> method

// Method 2: set modeUniformDistribution (mode: String, pct: double)
// use as seed for random numbers: beamConfig.matsim.modules.global.randomSeed
