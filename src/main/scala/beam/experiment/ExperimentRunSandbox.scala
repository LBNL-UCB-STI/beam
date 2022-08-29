package beam.experiment

import java.nio.file.{Files, Path, Paths}

import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}

/**
  * Created by dserdiuk on 11/25/17.
  */
case class ExperimentRunSandbox(
  experimentDef: ExperimentDef,
  experimentRun: ExperimentRun,
  experimentIdx: Int,
  beamTplConf: Config
) {

  lazy val runConfig: Config = buildRunConfig

  def scenariosDirectory: Path =
    Paths.get(
      experimentDef.header.experimentOutputRoot,
      "scenarios",
      s"${experimentDef.header.experimentId}",
      s"$experimentIdx"
    )

  def runsDirectory: Path =
    Paths.get(
      experimentDef.header.experimentOutputRoot,
      "runs",
      s"${experimentDef.header.experimentId}",
      s"$experimentIdx"
    )

  def modeChoiceParametersXmlPath: Path =
    Paths.get(scenariosDirectory.toString, "modeChoiceParameters.xml")

  def runBeamScriptPath: Path = Paths.get(scenariosDirectory.toString, "runBeam.sh")

  def beamConfPath: Path = {
    experimentDef.projectRoot.relativize(Paths.get(scenariosDirectory.toAbsolutePath.toString, "beam.conf"))
  }

  /**
    * @return path to an output folder relatively to project root
    */
  def beamOutputDir: Path = {
    experimentDef.projectRoot.relativize(Paths.get(runsDirectory.toAbsolutePath.toString, "output_"))
  }

  def getPathStringForConfig(path: Path): String = {
    // We need triple quotes because quotes cannot be escaped in string interpolation
    s"""$${BEAM_REPO_PATH}"/$path""""
  }

  def buildRunConfig: Config = {
    // set critical properties
    // beam.agentsim.agents.modalbehaviors.modeChoiceParametersFile
    // beam.outputs.baseOutputDirectory
    val runConfig: Config = (Map(
      "beam.agentsim.simulationName"               -> "output",
      "beam.outputs.addTimestampToOutputDirectory" -> "false"
    ) ++ modeChoiceConfigIfDefined ++ experimentRun.params)
      .foldLeft(beamTplConf) { case (prevConfig, (paramName, paramValue)) =>
        val configValue = ConfigValueFactory.fromAnyRef(paramValue)
        prevConfig.withValue(paramName, configValue)
      }

    // Removing the baseOutputDirectoryString is necessary in this way due to idiosyncratic behavior of withFallback.
    // Took a while to figure this out. DO NOT TRY TO SIMPLIFY!!! SAF 10/2019

    val outputString =
      s"""beam.outputs.baseOutputDirectory = ${getPathStringForConfig(beamOutputDir.getParent)}
         |beam.inputDirectory = ${getPathStringForConfig(Paths.get(experimentDef.header.beamScenarioDataInputRoot))}
         |""".stripMargin
    ConfigFactory
      .parseString(outputString)
      .withFallback(runConfig.withoutPath("beam.outputs.baseOutputDirectory").withoutPath("beam.inputDirectory"))

  }

  def modeChoiceConfigIfDefined: Map[_ <: String, String] = {
    experimentDef.header.modeChoiceTemplate match {
      case "" =>
        Map()
      case _ =>
        Map(
          "beam.agentsim.agents.modalbehaviors.modeChoiceParametersFile" -> experimentDef.projectRoot
            .relativize(modeChoiceParametersXmlPath)
            .toString
        )
    }
  }
}
