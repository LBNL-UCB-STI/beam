package beam.calibration

import java.nio.file.{Path, Paths}

import beam.calibration.impl.example.ModeChoiceObjectiveFunction
import beam.sim.BeamHelper
import com.sigopt.model.{Observation, Suggestion}
import com.typesafe.config.{Config, ConfigValueFactory}

import scala.collection.JavaConverters



case class ExperimentRunner(implicit  experimentData: SigoptExperimentData) extends BeamHelper {

  def runExperiment(numberOfIterations: Int): Unit = {
    // TODO: Make this part of experiment data
    val benchmarkData = Paths.get("test/input/beamville/example-experiment/benchmarkTest.csv").toAbsolutePath
    val f = new ModeChoiceObjectiveFunction(benchmarkData.toString)

    (0 to numberOfIterations).foreach{_ =>
      val suggestion = experimentData.experiment.suggestions.create.call
      val modedConfig = createConfigBasedOnSuggestion(suggestion)
      val (matsimConfig, outputDir) = runBeamWithConfig(modedConfig.resolve())
      val obs = new Observation.Builder().suggestion(suggestion.getId).value(f.evaluateFromRun(outputDir)).build()
      experimentData.experiment.observations().create(obs).call()
    }

  }

  def createConfigBasedOnSuggestion(suggestion: Suggestion)(implicit experimentData: SigoptExperimentData): Config = {
    val assignments = suggestion.getAssignments
    val runName = suggestion.getId
    val configParams = JavaConverters.iterableAsScalaIterable(assignments.entrySet()).seq.map { e => e.getKey -> e.getValue }.toMap

    val experimentBaseDir = Paths.get(experimentData.experimentPath.getParent).toAbsolutePath

    val runDirectory = experimentData.projectRoot.relativize(Paths.get(experimentBaseDir.toString, "runs", runName))

    val beamConfPath = experimentData.projectRoot.relativize(Paths.get(runDirectory.toString, "beam.conf").toAbsolutePath)

    val beamOutputDir: Path = experimentData.projectRoot.relativize(Paths.get(runDirectory.toString, "output").toAbsolutePath)

    (Map(
      "beam.agentsim.simulationName" -> "output",
      "beam.outputs.baseOutputDirectory" -> beamOutputDir.getParent.toString,
      "beam.outputs.addTimestampToOutputDirectory" -> "false",
      "beam.inputDirectory" -> experimentData.experimentDef.getTemplateConfigParentDirAsString
    ) ++ configParams).foldLeft(experimentData.baseConfig) {
      case (prevConfig, (paramName, paramValue)) =>
        val configValue = ConfigValueFactory.fromAnyRef(paramValue)
        prevConfig.withValue(paramName, configValue)
    }
  }

}
