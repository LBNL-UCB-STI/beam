package org.matsim.core.scenario

import org.matsim.api.core.v01.network.Network
import org.matsim.core.config.{Config => MatsimConfig}

class ScenarioBuilder(private val scenario: MutableScenario) {

  def withNetwork(network: Network): ScenarioBuilder = {
    scenario.setNetwork(network)
    this
  }

  def build: MutableScenario = scenario

}

object ScenarioBuilder {

  def apply(matsimConfig: MatsimConfig): ScenarioBuilder = {
    new ScenarioBuilder(new MutableScenario(matsimConfig))
  }

}
