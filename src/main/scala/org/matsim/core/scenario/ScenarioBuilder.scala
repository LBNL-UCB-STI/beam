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

object ScenarioBuilder {

  private val matsimConfigClazz = classOf[MatsimConfig]

  private val modules: util.TreeMap[String, ConfigGroup] = new util.TreeMap[String, ConfigGroup]()

  private def setEmptyPlans(matsimConfig: MatsimConfig): Unit = {
    val plans = new PlansConfigGroup

    val f1 = matsimConfig.getClass.getDeclaredField("plans")
    f1.setAccessible(true)
    f1.set(matsimConfig, plans)

    applyToModules(matsimConfig, PlansConfigGroup.GROUP_NAME, plans)
  }

  def setEmptyNetwork(matsimConfig: MatsimConfig): Unit = {
    val netConfig = new NetworkConfigGroup
    netConfig.setTimeVariantNetwork(false)

    val f1 = matsimConfig.getClass.getDeclaredField("network")
    f1.setAccessible(true)
    f1.set(matsimConfig, netConfig)

    applyToModules(matsimConfig, NetworkConfigGroup.GROUP_NAME, netConfig)
  }

  def setStrategy(matsimConfig: MatsimConfig): Unit = {
    val strategy: StrategyConfigGroup = new StrategyConfigGroup

    val field = matsimConfig.getClass.getDeclaredField("strategy")
    field.setAccessible(true)
    field.set(matsimConfig, strategy)

    applyToModules(matsimConfig, StrategyConfigGroup.GROUP_NAME, strategy)
  }

  def setEmptyModules(matsimConfig: MatsimConfig): Unit = {
    val field = matsimConfig.getClass.getDeclaredField("modules")
    field.setAccessible(true)
    field.set(matsimConfig, modules)
  }

  def applyToModules(matsimConfig: MatsimConfig, groupName: String, config: ConfigGroup): Unit = {
    modules.put(groupName, config)
  }

  private def buildMatsimConfig: MatsimConfig = {
    val result = new MatsimConfig()
    setEmptyModules(result)
    setEmptyNetwork(result)
    setEmptyPlans(result)
    setStrategy(result)
    result
  }

  def builder: ScenarioBuilder = new ScenarioBuilder(new MutableScenario(buildMatsimConfig))

}
