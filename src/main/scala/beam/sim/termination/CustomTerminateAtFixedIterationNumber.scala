package beam.sim.termination

import beam.sim.config.BeamConfigHolder
import javax.inject.Inject
import org.matsim.core.config.groups.ControlerConfigGroup
import org.matsim.core.controler.TerminationCriterion

class CustomTerminateAtFixedIterationNumber extends TerminationCriterion {
  private var lastIteration = 0
  private var beamConfigHolder: BeamConfigHolder = _

  @Inject
  def this(controlerConfigGroup: ControlerConfigGroup, beamConfigHolder: BeamConfigHolder) {
    this()
    this.beamConfigHolder = beamConfigHolder
    this.lastIteration = controlerConfigGroup.getLastIteration
  }

  override def continueIterations(iteration: Int): Boolean = {
    lastIteration = beamConfigHolder.beamConfig.beam.agentsim.lastIteration
    iteration <= lastIteration
  }

}
