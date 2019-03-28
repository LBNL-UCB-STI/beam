package beam.calibration

import java.nio.file.Paths

import beam.calibration.BeamSigoptTuner.{createExperiment, fetchExperiment}
import beam.experiment.ExperimentDef
import com.sigopt.model.Experiment
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging

import scala.util.Try

case class SigoptExperimentData(
  experimentDef: ExperimentDef,
  benchmarkFileLoc: String,
  experimentId: String,
  development: Boolean = false
) extends LazyLogging {

  val baseConfig: Config =
    ConfigFactory.parseFile(Paths.get(experimentDef.getHeader.getBeamTemplateConfPath).toFile)
      .withFallback(ConfigFactory.parseString(s"config=${experimentDef.getHeader.getBeamTemplateConfPath}"))

  // Always default to single JVM if incorrect entry
  val numWorkers: Int = Try {
    experimentDef.header.numWorkers.toInt
  }.getOrElse(1)

  val isParallel: Boolean = numWorkers > 1

  val isMaster: Boolean = experimentId == "None"

  val experiment: Experiment =
    fetchExperiment(experimentId) match {
      case Some(foundExperiment) =>
        logger.info(s"Retrieved the existing experiment with experiment id $experimentId")
        if (isParallel) {
          Experiment.update(foundExperiment.getId).data(s"""{"parallel_bandwidth":$numWorkers}""").call()
        }
        foundExperiment
      case None =>
        val createdExperiment: Experiment = createExperiment(experimentDef)
        logger.info("New Experiment created with experimentId [" + createdExperiment.getId + "]")
        createdExperiment
    }

}
