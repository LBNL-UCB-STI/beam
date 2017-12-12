package beam.experiment

import java.nio.file.{Files, Path, Paths}

import com.typesafe.config.{Config, ConfigValueFactory}

/**
  * Created by dserdiuk on 11/25/17.
  */
case class ExperimentRunSandbox(projectRoot: Path, experimentBaseDir: Path, experimentDef: ExperimentDef, experimentRun: ExperimentRun, beamTplConf: Config) {
  require(Files.exists(experimentBaseDir))

  lazy val runConfig: Config = buildRunConfig

  def runDirectory = Paths.get(experimentBaseDir.toString,
    "runs", s"run.${experimentRun.name}"
  )

  def modeChoiceParametersXmlPath = Paths.get(runDirectory.toString, "modeChoiceParameters.xml")

  def runExperimentScriptPath = Paths.get(runDirectory.toString, "runExperiment.sh")

  def batchRunScriptPath = Paths.get(runDirectory.getParent.getParent.toString, "batchRunExperiment.sh")

  def beamConfPath = {
    projectRoot.relativize(Paths.get(runDirectory.toString, "beam.conf"))
  }

  /**
    *
    * @return path to an output folder relatively to project root
    */
  def beamOutputDir = {
    projectRoot.relativize(Paths.get(runDirectory.toString, "output"))
  }

  def buildRunConfig = {
    // set critical properties
    // beam.agentsim.agents.modalBehaviors.modeChoiceParametersFile
    // beam.outputs.outputDirectory
    val runConfig = ( Map(
        "beam.agentsim.agents.modalBehaviors.modeChoiceParametersFile" -> projectRoot.relativize(modeChoiceParametersXmlPath).toString,
        "beam.outputs.outputDirectory" -> beamOutputDir.toString
    ) ++ experimentRun.params).foldLeft(beamTplConf) { case (prevConfig, (paramName, paramValue)) =>
        val configValue = ConfigValueFactory.fromAnyRef(paramValue)
        prevConfig.withValue(paramName, configValue)
    }
    runConfig
  }
}
