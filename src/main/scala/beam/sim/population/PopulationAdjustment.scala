package beam.sim.population

import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.population.Population

trait PopulationAdjustment {
  def update(population: Population): Population
}

object PopulationAdjustment {
  val DEFAULT_ADJUSTMENT = "DEFAULT_ADJUSTMENT"
  val PERCENTAGE_ADJUSTMENT = "PERCENTAGE_ADJUSTMENT"

  def getPopulationAdjustment(adjKey: String, beamConfig: BeamConfig): PopulationAdjustment = {
    adjKey match {
      case DEFAULT_ADJUSTMENT =>
        new DefaultPopulationAdjustment(beamConfig)
      case PERCENTAGE_ADJUSTMENT =>
        new PercentagePopulationAdjustment(beamConfig)
      case adjClass =>
        try {
          Class
            .forName(adjClass)
            .getDeclaredConstructors()(0)
            .newInstance(beamConfig)
            .asInstanceOf[PopulationAdjustment]
        } catch {
          case e: Exception =>
            throw new IllegalStateException(s"Unknown PopulationAdjustment: $adjClass", e)
        }
    }
  }
}
