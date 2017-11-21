package beam.experiment

import java.io._
import java.nio.file.{Files, Path, Paths}

import com.google.common.base.Charsets
import com.google.common.io.Resources
import com.hubspot.jinjava.Jinjava
import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions, ConfigValueFactory}
import org.yaml.snakeyaml.constructor.Constructor

import scala.collection.JavaConverters._

/**
  * Generate beam.conf and run script for individual run.
  *  Couple notes:
  *  1. paths n config and templates should be relative to project root
  *  2. --experiments is used to pass location of experiments.yml
  *
  *  This generator will create sub-directories relatively to experiments.yml
  *
  */
object ExperimentGenerator extends App {

  val ExperimentsParamName = "experiments"

  private val projectRoot = {
    if (System.getenv("BEAM_ROOT") != null) {
      Paths.get(System.getenv("BEAM_ROOT"))
    } else {
      Paths.get("./").toAbsolutePath.getParent
    }
  }
  val argsMap = parseArgs(args)

  if (argsMap.get(ExperimentsParamName).isEmpty) {
    throw new IllegalArgumentException(s"$ExperimentsParamName param is missing")
  }
  private val experimentFile = new File(argsMap(ExperimentsParamName)).toPath.toAbsolutePath
  if (!Files.exists(experimentFile)) {
    throw new IllegalArgumentException(s"$ExperimentsParamName file is missing")
  }

  val experiment = loadExperimentDefs(experimentFile.toFile)

  private def loadExperimentDefs(file: File) = {
    import org.yaml.snakeyaml.{TypeDescription, Yaml}
    val constructor = new Constructor(classOf[ExperimentDef])
    //Experiment.class is root
    val experimentDescription = new TypeDescription(classOf[ExperimentDef])
    experimentDescription.putListPropertyType("factors", classOf[Factor])
    constructor.addTypeDescription(experimentDescription)
    val factorsDescription = new TypeDescription(classOf[Factor])
    factorsDescription.putListPropertyType("levels", classOf[Level])
    constructor.addTypeDescription(factorsDescription)
    val levelDesc = new TypeDescription(classOf[Level])
    //levelDesc.putMapPropertyType("params", classOf[String], classOf[Any])
    constructor.addTypeDescription(levelDesc)
    val baseScenarioDesc = new TypeDescription(classOf[BaseScenario])
    factorsDescription.putListPropertyType("baseScenario", classOf[BaseScenario])
    constructor.addTypeDescription(baseScenarioDesc)
    val yaml = new Yaml(constructor)
    val experiment = yaml.loadAs(new FileInputStream(file), classOf[ExperimentDef])
    experiment
  }

  validateExperimentConfig(experiment)
  val runScriptTemplateFile = Paths.get(experiment.runExperimentScript).toAbsolutePath
  val modeChoiceTemplateFile = Paths.get(experiment.modeChoiceTemplate).toAbsolutePath
  val baseConfig = ConfigFactory.parseFile(Paths.get(experiment.beamTemplateConfPath).toFile)
  val baseScenarioRun = ExperimentRunSandbox(projectRoot, experimentFile.getParent, experiment, ExperimentRun(experiment.baseScenario, combinations = List()), baseConfig)
  val runs =  baseScenarioRun :: experiment.combinationsOfLevels().map { run =>
    ExperimentRunSandbox(projectRoot, experimentFile.getParent, experiment, run, baseConfig)
  }

  val jinjava = new Jinjava()
  val modeChoiceTemplate = Resources.toString(modeChoiceTemplateFile.toUri.toURL, Charsets.UTF_8)
  val runScriptTemplate = Resources.toString(runScriptTemplateFile.toUri.toURL, Charsets.UTF_8)

  runs.foreach { runSandbox =>
    if (!Files.exists(runSandbox.beamConfPath.getParent)) {
      runSandbox.beamConfPath.getParent.toFile.mkdirs()
    }

    val beamConfWriter = new BufferedWriter(new FileWriter(runSandbox.beamConfPath.toFile, false))
    try {

      val beamConfStr = runSandbox.runConfig.root().render(ConfigRenderOptions.concise().setJson(false).setFormatted(true))
      beamConfWriter.write(beamConfStr)
      beamConfWriter.flush()
    } finally {
      beamConfWriter.close()
    }

    if (!Files.exists(runSandbox.modeChoiceParametersXmlPath.getParent)) {
      runSandbox.modeChoiceParametersXmlPath.getParent.toFile.mkdirs()
    }
    val modeChoiceWriter = new BufferedWriter(new FileWriter(runSandbox.modeChoiceParametersXmlPath.toFile, false))
    val templateParams = Map(
      "BEAM_CONFIG_PATH" -> runSandbox.beamConfPath.toString,
      "BEAM_OUTPUT_PATH" -> runSandbox.beamOutputDir.toString
//      ,
//      "BEAM_SHARED_INPUT" -> runSandbox.beamOutputDir.toString

    ) ++ runSandbox.experimentRun.params
    try {
      val renderedTemplate = jinjava.render(modeChoiceTemplate, templateParams.asJava)
      modeChoiceWriter.write(renderedTemplate)
      modeChoiceWriter.flush()
    } finally {
      modeChoiceWriter.close()
    }
    if (!Files.exists(runSandbox.runExperimentScriptPath.getParent)) {
      runSandbox.runExperimentScriptPath.getParent.toFile.mkdirs()
    }

    val runScriptWriter = new BufferedWriter(new FileWriter(runSandbox.runExperimentScriptPath.toFile, false))
    try {
      val renderedTemplate = jinjava.render(runScriptTemplate, templateParams.asJava)
      runScriptWriter.write(renderedTemplate)
      runScriptWriter.flush()
    } finally {
      runScriptWriter.close()
    }

    Runtime.getRuntime.exec(s"chmod +x ${runSandbox.runExperimentScriptPath.toFile.toString}")

  }


  private def validateExperimentConfig(experiment: ExperimentDef) = {
    if (!Files.exists(Paths.get(experiment.beamTemplateConfPath))) {
      throw new IllegalArgumentException(s"Can't locate base beam config experimentFile at ${experiment.beamTemplateConfPath}")
   }
    val runScriptTemplateFile = Paths.get(experiment.runExperimentScript).toAbsolutePath
    if (!Files.exists(runScriptTemplateFile)) {
      throw new IllegalArgumentException("No runs script template found " + runScriptTemplateFile.toString)
    }

    val modeChoiceTemplateFile = Paths.get(experiment.modeChoiceTemplate).toAbsolutePath
    if (!Files.exists(modeChoiceTemplateFile)) {
      throw new IllegalArgumentException("No mode choice template found " + modeChoiceTemplateFile.toString)
    }
  }

  def parseArgs(args: Array[String]) = {

    args.sliding(2, 1).toList.collect {
      case Array("--experiments", filePath: String) if filePath.trim.nonEmpty => (ExperimentsParamName, filePath)
      case arg@_ =>
        throw new IllegalArgumentException(arg.mkString(" "))
    }.toMap
  }

}

case class ExperimentRunSandbox(projectRoot: Path, experimentBaseDir: Path, experimentDef: ExperimentDef, experimentRun: ExperimentRun, beamTplConf: Config) {
  require(Files.exists(experimentBaseDir))

  lazy val runConfig: Config = buildRunConfig

  def runDirectory = Paths.get(experimentBaseDir.toString,
    experimentDef.title.replace("\\s", "_"),
    "runs", s"run.${experimentRun.name}"
  )

  def modeChoiceParametersXmlPath = Paths.get(runDirectory.toString, "modeChoiceParameters.xml")

  def runExperimentScriptPath = Paths.get(runDirectory.toString, "runExperiment.sh")

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