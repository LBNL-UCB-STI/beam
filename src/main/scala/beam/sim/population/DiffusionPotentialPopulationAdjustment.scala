package beam.sim.population

import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.Population

class DiffusionPotentialPopulationAdjustment(beamConfig: BeamConfig) extends PopulationAdjustment with LazyLogging {
  override def updatePopulation(population: Population): Population = {

    adjustPopulationByDiffusionPotential(population, "ride_hail")

    population
  }

  def adjustPopulationByDiffusionPotential(population: Population, mode: String): Unit = {

    population.getPersons.keySet().forEach { key =>
      val personId = key.toString
      val diffPotential = if (mode.toLowerCase.contains("ride_hail"))
        computeRideHailDiffusionPotential(population, personId)
      else
        computeAutomatedVehicleDiffusionPotential(population, personId)

      if (diffPotential > 0) {
        removeMode(population, personId, mode)
      }
    }
  }

  def computeRideHailDiffusionPotential(population: Population, personId: String): Double = {
    0
  }

  def computeAutomatedVehicleDiffusionPotential(population: Population, personId: String): Double = {
    0
  }
}

object DiffusionPotentialPopulationAdjustment {
  val dependentVariables = Map(
    "RIDE_HAIL_SINGLE" -> Map(
      "1980" -> 0.2654,
      "1990" -> 0.2706,
      "child<8" -> -0.1230,
      "income>200K" -> 0.1252,
      "walk-score" -> 0.0006,
      "constant" -> 0.1947),
    "RIDE_HAIL_CARPOOL" -> Map(
      "1980" -> 0.2196,
      "1990" -> 0.2396,
      "child<8" -> -0.1383,
      "income>200K" -> 0.0159,
      "walk-score" -> 0.0014,
      "constant" -> 0.2160),
    "AUTOMATED_VEHICLE" -> Map(
      "1940" -> 0.1296,
      "1990" -> 0.2278,
      "income[75K-150K)" -> 0.0892,
      "income[150K-200K)" -> 0.1410,
      "income>200K" -> 0.1925,
      "female" -> -0.2513,
      "disability" -> -0.3061,
      "constant" -> 0.4558
    )
  )
}
