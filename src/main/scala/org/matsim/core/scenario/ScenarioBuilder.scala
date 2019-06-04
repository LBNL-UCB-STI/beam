package org.matsim.core.scenario

import java.util

import org.matsim.api.core.v01.network.Network
import org.matsim.core.config.groups.{HouseholdsConfigGroup, NetworkConfigGroup, PlansConfigGroup, StrategyConfigGroup}
import org.matsim.core.config.{ConfigGroup, Config => MatsimConfig}
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.pt.config.TransitConfigGroup

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

  def setHouseHolds(matsimConfig: MatsimConfig): Unit = {
    val r = new HouseholdsConfigGroup
    val field = matsimConfig.getClass.getDeclaredField("households")
    field.setAccessible(true)
    field.set(matsimConfig, r)

    applyToModules(matsimConfig, HouseholdsConfigGroup.GROUP_NAME, r)
  }

  def setTransit(matsimConfig: MatsimConfig): Unit = {
    val r = new TransitConfigGroup
    val field = matsimConfig.getClass.getDeclaredField("transit")
    field.setAccessible(true)
    field.set(matsimConfig, r)

    applyToModules(matsimConfig, TransitConfigGroup.GROUP_NAME, r)
  }

  private def buildMatsimConfig: MatsimConfig = {
    val result = new MatsimConfig()
    result.addCoreModules()
    result.controler().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles)
//    setEmptyModules(result)
//    setEmptyNetwork(result)
//    setEmptyPlans(result)
//    setStrategy(result)
//    setHouseHolds(result)
//    setTransit(result)
//    setVehicles(result)
    result
  }

  def builder: ScenarioBuilder = new ScenarioBuilder(new MutableScenario(buildMatsimConfig))

}
