package beam.utils

import beam.agentsim.events.PathTraversalEvent
import beam.analysis.plots.DeadHeadingAnalysis
import beam.sim.metrics.NoOpSimulationMetricCollector
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting
import org.matsim.core.controler.events.IterationEndsEvent

object DeadHeadingAnalysisMain {

  def main(args: Array[String]): Unit = {
    val outputDirectoryHierarchy = new OutputDirectoryHierarchy("temp", OverwriteFileSetting.deleteDirectoryIfExists)
    outputDirectoryHierarchy.createIterationDirectory(10)

    val matsimService = new SimplifiedMatsimServices(outputDirectoryHierarchy)
    val analysis = new DeadHeadingAnalysis(NoOpSimulationMetricCollector, true, matsimService.getControlerIO)

    val path =
      """https://beam-outputs.s3.amazonaws.com/output/newyork/nyc-200k-07-3__2020-09-03_15-57-51_mor/ITERS/it.10/10.events.csv.gz"""
    val (events, toClose) = EventReader.fromCsvFile(path, event => event.getEventType == PathTraversalEvent.EVENT_TYPE)
    try {
      ProfilingUtils.timed("Create graphs", x => println(x)) {
        val ptes = events.map(PathTraversalEvent.apply(_))
        ptes.foreach(analysis.collectEvents)

        analysis.createGraph(new IterationEndsEvent(matsimService, 10))
      }
    } finally {
      toClose.close()
    }

  }
}
