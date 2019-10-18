package beam.sim

import java.util

import beam.sim.config.BeamConfig
import beam.sim.metrics.Metrics
import beam.sim.metrics.Metrics.isMetricsEnable
import com.typesafe.scalalogging.LazyLogging
import javax.inject.{Inject, Provider}
import kamon.Kamon
import org.matsim.analysis.IterationStopWatch
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.listener.ControlerListener
import org.matsim.core.controler.{ControlerListenerManagerImpl, OutputDirectoryHierarchy, OutputDirectoryLogging, PrepareForSim}
import org.matsim.core.gbl.MatsimRandom
import org.matsim.core.mobsim.framework.Mobsim

class BeamController @Inject()(
  val config: BeamConfig,
  val outputDirectoryHierarchy: OutputDirectoryHierarchy,
  val prepareForSim: PrepareForSim,
  val eventsManager: EventsManager,
  val controlerListenerManager: ControlerListenerManagerImpl,
  val mobsimProvider: Provider[Mobsim],
  val controlerListenersDeclaredByModules: util.Set[ControlerListener]
                              ) extends LazyLogging {

  val configCreateGraphs = true
  val configDumpDataAtEnd = false
  val stopWatch: IterationStopWatch = new IterationStopWatch

  import scala.collection.JavaConversions._

  for (controlerListener <- this.controlerListenersDeclaredByModules) {
    controlerListenerManager.addControlerListener(controlerListener)
  }

  def run() {
    OutputDirectoryLogging.initLogging(outputDirectoryHierarchy)
    try {
      controlerListenerManager.fireControlerStartupEvent()
      prepareForSim.run()
      var thisIteration: Int = config.matsim.modules.controler.firstIteration;
      while (thisIteration <= config.matsim.modules.controler.lastIteration) {
        iteration(config, thisIteration);
        thisIteration = thisIteration + 1
      }
    } finally {
      // controlerListenerManager.fireControlerShutdownEvent(false)
    }
    OutputDirectoryLogging.closeOutputDirLogging()
    if (isMetricsEnable) Kamon.shutdown()
  }

  private def iteration(config: BeamConfig, iteration: Int): Unit = {
    Metrics.iterationNumber = iteration
    stopWatch.beginIteration(iteration)
    logger.info(DIVIDER)
    logger.info(MARKER + "ITERATION " + iteration + " BEGINS")
    outputDirectoryHierarchy.createIterationDirectory(iteration)
    resetRandomNumbers(config.matsim.modules.global.randomSeed, iteration)
    iterationStep("iterationStartsListeners", () => controlerListenerManager.fireControlerIterationStartsEvent(iteration))
    if (iteration > config.matsim.modules.controler.firstIteration) iterationStep("replanning", () => controlerListenerManager.fireControlerReplanningEvent(iteration))
    try {
      eventsManager.resetHandlers(iteration)
      iterationStep("beforeMobsimListeners", () => controlerListenerManager.fireControlerBeforeMobsimEvent(iteration))
      iterationStep("mobsim", () => {
        resetRandomNumbers(config.matsim.modules.global.randomSeed, iteration)
        mobsimProvider.get().run()
      })
    } finally iterationStep(
      "afterMobsimListeners",
      () => {
        logger.info(MARKER + "ITERATION " + iteration + " fires after mobsim event")
        controlerListenerManager.fireControlerAfterMobsimEvent(iteration)
      }
    )
    iterationStep(
      "scoring",
      () => {
        logger.info(MARKER + "ITERATION " + iteration + " fires scoring event")
        controlerListenerManager.fireControlerScoringEvent(iteration)
      }
    )
    iterationStep(
      "iterationEndsListeners",
      () => {
        logger.info(MARKER + "ITERATION " + iteration + " fires iteration end event")
        controlerListenerManager.fireControlerIterationEndsEvent(iteration)
      }
    )
    stopWatch.endIteration()
    stopWatch.writeTextFile(outputDirectoryHierarchy.getOutputFilename("stopwatch"))
    if (configCreateGraphs) stopWatch.writeGraphFile(outputDirectoryHierarchy.getOutputFilename("stopwatch"))
    logger.info(MARKER + "ITERATION " + iteration + " ENDS")
    logger.info(DIVIDER)
  }

  private def iterationStep(iterationStepName: String, iterationStep: Runnable): Unit = {
    stopWatch.beginOperation(iterationStepName)
    iterationStep.run()
    stopWatch.endOperation(iterationStepName)
    if (Thread.interrupted) throw new RuntimeException
  }

  private def resetRandomNumbers(seed: Long, iteration: Int): Unit = {
    MatsimRandom.reset(seed + iteration)
    MatsimRandom.getRandom.nextDouble
  }

  val DIVIDER = "###################################################"
  val MARKER = "### "

}
