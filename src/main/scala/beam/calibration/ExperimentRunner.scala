package beam.calibration

import java.nio.file.{Path, Paths}

import scala.collection.{mutable, JavaConverters}

import org.matsim.core.config.{Config => MatsimConfig}

import com.sigopt.model.{Observation, Suggestion}
import com.typesafe.config.{Config, ConfigValueFactory}

import beam.analysis.plots.GraphsStatsAgentSimEventsListener
import beam.calibration.api.{FileBasedObjectiveFunction, ObjectiveFunction}
import beam.calibration.impl.example.{CountsObjectiveFunction, ModeChoiceObjectiveFunction}
import beam.sim.BeamHelper
import beam.utils.reflection.ReflectionUtils

object ObjectiveFunctionClassBuilder extends ReflectionUtils {
  override def packageName: String = "beam.calibration"
}

case class ExperimentRunner(implicit experimentData: SigoptExperimentData) extends BeamHelper {

  import beam.utils.ProfilingUtils.timed

  val objectiveFunctionClassName: String = { experimentData.baseConfig.getString("beam.calibration.objectiveFunction") }

  def runExperiment(numberOfIterations: Int): Unit = {

    logger.info(
      s"Starting BEAM SigOpt optimization for ${experimentData.experiment.getName} with ID ${experimentData.experiment.getId}\n"
    )

    (0 to numberOfIterations).foreach { iter =>
      logger.info(logExpHelper(s"Starting iteration, $iter of $numberOfIterations"))

      val suggestion = experimentData.experiment.suggestions.create.call
      logger.info(logExpHelper(s"Received new suggestion (ID: ${suggestion.getId})."))

      val modedConfig: Config = createConfigBasedOnSuggestion(suggestion)
      logger.info(
        logExpHelper(
          s"Created new config based on suggestion ${suggestion.getId}, starting BEAM..."
        )
      )

      val ((matsimConfig, _), execTimeInMillis) = timed {
        runBeamWithConfig(modedConfig.resolve())
      }

      logger.info(
        logExpHelper(
          s"BEAM run completed for suggestion ${suggestion.getId} in ${execTimeInMillis / 1000} seconds"
        )
      )

      val x = getRunValue(matsimConfig)

      val obs = new Observation.Builder()
        .suggestion(suggestion.getId)
        .value(x)
        .build()

      logger.info(logExpHelper(s"Uploading new observation (value: ${obs.getValue})."))

      experimentData.experiment.observations().create(obs).call()
      logger.info(logExpHelper(s"Iteration $iter completed\n"))

    }

//    val bestAssignments: BestAssignments =
//      experimentData.experiment.bestAssignments().fetch().call()

  }

  def getRunValue(runConfig: MatsimConfig): Double = {
    if (objectiveFunctionClassName.equals("CountsObjectiveFunction")) {
      val outpath = Paths.get(
        GraphsStatsAgentSimEventsListener.CONTROLLER_IO
          .getIterationFilename(runConfig.controler().getLastIteration, "countscompare.txt")
      )
      CountsObjectiveFunction.evaluateFromRun(outpath.toAbsolutePath.toString)
    } else {
      val benchmarkData = Paths.get(experimentData.benchmarkFileLoc).toAbsolutePath
      val outpath = Paths.get(GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputFilename("modeChoice.csv"))
      new ModeChoiceObjectiveFunction(benchmarkData.toAbsolutePath.toString)
        .evaluateFromRun(outpath.toAbsolutePath.toString)
    }
  }

  def createConfigBasedOnSuggestion(
    suggestion: Suggestion
  )(implicit experimentData: SigoptExperimentData): Config = {
    val assignments = suggestion.getAssignments

    val experimentName: String = suggestion.getExperiment

    val suggestionId: String = suggestion.getId

    val configParams: mutable.Map[String, Object] = JavaConverters.mapAsScalaMap(
      experimentData.experimentDef.defaultParams
    ) ++
    JavaConverters
      .iterableAsScalaIterable(assignments.entrySet())
      .seq
      .map { e =>
        e.getKey -> e.getValue
      }
      .toMap

    val experimentBaseDir = Paths.get(experimentData.experimentPath.getParent).toAbsolutePath

    val runDirectory = experimentData.projectRoot.relativize(
      Paths.get(experimentBaseDir.toString, "experiments", experimentName, "suggestions")
    )

    val beamOutputDir: Path = experimentData.projectRoot.relativize(
      Paths.get(runDirectory.toString, suggestionId).toAbsolutePath
    )

    (Map(
      "beam.agentsim.simulationName"               -> s"$suggestionId",
      "beam.outputs.baseOutputDirectory"           -> beamOutputDir.getParent.toString,
      "beam.outputs.addTimestampToOutputDirectory" -> "false",
      "beam.inputDirectory"                        -> experimentData.experimentDef.getTemplateConfigParentDirAsString
    ) ++ configParams).foldLeft(experimentData.baseConfig) {
      case (prevConfig, (paramName, paramValue)) =>
        val configValue = ConfigValueFactory.fromAnyRef(paramValue)
        prevConfig.withValue(paramName, configValue)
    }
  }

  def logExpHelper(msg: String): String = {
    s"[ExpID: ${experimentData.experiment.getId}] $msg"
  }

}
