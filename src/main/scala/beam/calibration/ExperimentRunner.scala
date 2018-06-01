package beam.calibration

import java.nio.file.{Path, Paths}

import beam.calibration.api.{FileBasedObjectiveFunction, ObjectiveFunction}
import beam.sim.BeamHelper
import beam.utils.reflection.ReflectionUtils
import com.sigopt.model.{Observation, Suggestion}
import com.typesafe.config.{Config, ConfigValueFactory}

import scala.collection.{JavaConverters, mutable}

object ObjectiveFunctionClassBuilder extends ReflectionUtils {
  override def packageName: String = "beam.calibration"
}

case class ExperimentRunner(implicit experimentData: SigoptExperimentData) extends BeamHelper {

  def runExperiment(numberOfIterations: Int): Unit = {

    val benchmarkData = Paths.get(experimentData.benchmarkFileLoc).toAbsolutePath

    val objectiveFunctionClassName = s"${ObjectiveFunctionClassBuilder.packageName}${experimentData.baseConfig.atKey("beam.calibration.objectiveFunction")}"

    val objectiveFunction: ObjectiveFunction = ObjectiveFunctionClassBuilder.concreteClassesOfType[ObjectiveFunction].collect {
      case clazz if ObjectiveFunctionClassBuilder.isExtends(clazz, classOf[FileBasedObjectiveFunction]) =>
            clazz.getConstructor(classOf[String]).newInstance(benchmarkData.toString)
    }.head



    (0 to numberOfIterations).foreach { _ =>
      val suggestion = experimentData.experiment.suggestions.create.call
      val modedConfig = createConfigBasedOnSuggestion(suggestion)
      val (matsimConfig, outputDir) = runBeamWithConfig(modedConfig.resolve())
      val obs = new Observation.Builder().suggestion(suggestion.getId).value(objectiveFunction.evaluateFromRun(outputDir)).build()
      experimentData.experiment.observations().create(obs).call()
    }

  }

  def createConfigBasedOnSuggestion(suggestion: Suggestion)(implicit experimentData: SigoptExperimentData): Config = {
    val assignments = suggestion.getAssignments

    val experimentName: String = suggestion.getExperiment

    val suggestionId: String = suggestion.getId

    val configParams: mutable.Map[String, Object] = JavaConverters.mapAsScalaMap(experimentData.experimentDef.defaultParams) ++ JavaConverters.iterableAsScalaIterable(assignments.entrySet()).seq.map { e => e.getKey -> e.getValue }.toMap

    val experimentBaseDir = Paths.get(experimentData.experimentPath.getParent).toAbsolutePath

    val runDirectory = experimentData.projectRoot.relativize(Paths.get(experimentBaseDir.toString, "experiments", experimentName, "suggestions"))

    val beamConfPath = experimentData.projectRoot.relativize(Paths.get(runDirectory.toString, "beam.conf").toAbsolutePath)

    val beamOutputDir: Path = experimentData.projectRoot.relativize(Paths.get(runDirectory.toString, suggestionId).toAbsolutePath)

    (Map(
      "beam.agentsim.simulationName" -> s"$suggestionId",
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
