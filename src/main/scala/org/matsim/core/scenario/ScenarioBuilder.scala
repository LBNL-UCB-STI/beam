package org.matsim.core.scenario

import java.util
import java.util.TreeMap

import org.matsim.api.core.v01.network.Network
import org.matsim.core.config.groups.{NetworkConfigGroup, PlansConfigGroup, StrategyConfigGroup}
import org.matsim.core.config.{ConfigGroup, Config => MatsimConfig}

class ScenarioBuilder(private val scenario: MutableScenario) {

  def withNetwork(network: Network): ScenarioBuilder = {
    scenario.setNetwork(network)
    this
  }
  def build: MutableScenario = scenario
}

object ScenarioBuilder extends App {

  private val matsimConfigClazz = classOf[MatsimConfig]

  private val modules: util.TreeMap[String, ConfigGroup] = new util.TreeMap[String, ConfigGroup]

  private def setEmptyPlans(matsimConfig: MatsimConfig): Unit = {
    val f1 = matsimConfig.getClass.getDeclaredField("plans")
    f1.setAccessible(true)
    f1.set(matsimConfig, new PlansConfigGroup)
  }

  def setEmptyNetwork(matsimConfig: MatsimConfig): Unit = {
    val netConfig = new NetworkConfigGroup
    netConfig.setTimeVariantNetwork(false)
    val f1 = matsimConfig.getClass.getDeclaredField("network")
    f1.setAccessible(true)
    f1.set(matsimConfig, netConfig)
  }

  def applytoModules(matsimConfig: MatsimConfig, GROUP_NAME: String, strategy: StrategyConfigGroup): Unit = {
    val modules = matsimConfig.getClass.getDeclaredField("modules")
    modules.setAccessible(true)

  }

  def setStrategy(matsimConfig: MatsimConfig): Unit = {
    val strategy: StrategyConfigGroup = new StrategyConfigGroup

    val field = matsimConfig.getClass.getDeclaredField("strategy")
    field.setAccessible(true)
    field.set(matsimConfig, strategy)

    applytoModules(StrategyConfigGroup.GROUP_NAME, strategy)
//    this.modules.put(StrategyConfigGroup.GROUP_NAME, this.strategy)

  }

  private def buildMatsimConfig: MatsimConfig = {
    val result = new MatsimConfig()
    setEmptyNetwork(result)
    setEmptyPlans(result)
    result
  }

  def builder: ScenarioBuilder = new ScenarioBuilder(new MutableScenario(buildMatsimConfig))

}
