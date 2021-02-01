package beam.sim.population

import beam.sim.{BeamScenario, BeamServices}
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.Population

/**
  * A PopulationAdjustment implementation that removes all available modes from persons except for car, ride hail, and walk.
  *
  * @param beamServices required to access the Population data.
  */
case class CarRideHailOnly(beamServices: BeamServices) extends PopulationAdjustment {

  override lazy val scenario: Scenario = beamServices.matsimServices.getScenario
  override lazy val beamScenario: BeamScenario = beamServices.beamScenario

  override def updatePopulation(scenario: Scenario): Population = {
    val population = scenario.getPopulation

    removeModeAll(population, "cav")
    removeModeAll(population, "transit")
    removeModeAll(population, "walk_transit")
    removeModeAll(population, "drive_transit")
    removeModeAll(population, "bike_transit")
    removeModeAll(population, "ride_hail_transit")
    removeModeAll(population, "bike")

    population
  }

}
