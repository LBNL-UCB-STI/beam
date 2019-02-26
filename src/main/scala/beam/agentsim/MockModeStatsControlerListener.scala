package org.matsim.analysis

import javax.inject.{Inject, Provider}
import org.matsim.api.core.v01.population.Population
import org.matsim.core.config.groups.{ControlerConfigGroup, PlanCalcScoreConfigGroup}
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.router.TripRouter

class MockModeStatsControlerListener @Inject()(controlerConfigGroup: ControlerConfigGroup,
                                               population1: Population,
                                               controlerIO: OutputDirectoryHierarchy,
                                               scoreConfig: PlanCalcScoreConfigGroup,
                                               tripRouterFactory:Provider[TripRouter])
  extends ModeStatsControlerListener(controlerConfigGroup, population1, controlerIO, scoreConfig, tripRouterFactory) {

  //println("Inside mocked ModeStatsControlerListener")

}
