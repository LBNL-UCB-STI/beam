package org.matsim.core.scenario

import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.population.Population
import org.matsim.core.config.{Config => MatsimConfig}
import org.matsim.core.population.PopulationUtils

class ScenarioBuilder(private val matsimConfig: MatsimConfig, private val network: Network) {

  def buildPopulation: Population = PopulationUtils.createPopulation(matsimConfig, network)

  def build: MutableScenario = {
    val r = new MutableScenario(matsimConfig)
    r.setNetwork(network)
    r
  }

}

object ScenarioBuilder {

  def apply(matsimConfig: MatsimConfig, network: Network): ScenarioBuilder = {
    new ScenarioBuilder(matsimConfig, network)
  }

}
