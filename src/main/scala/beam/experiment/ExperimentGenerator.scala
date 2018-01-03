package beam.experiment

import java.io._
import java.nio.file.{Files, Paths}

import com.google.common.base.Charsets
import com.google.common.io.Resources
import com.hubspot.jinjava.Jinjava
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}
import org.apache.commons.io.IOUtils
import org.yaml.snakeyaml.constructor.Constructor

import scala.collection.JavaConverters._

/**
  * Generate beam.conf and run script for individual run.
  * Couple notes:
  *  1. paths n config and templates should be relative to project root
  *  2. --experiments is used to pass location of experiments.yml
  *
  * This generator will create sub-directories relatively to experiments.yml
  *
  */
object ExperimentGenerator extends App {

  val ExperimentsParamName = "experiments"

  private def validateExperimentConfig(experiment: ExperimentDef) = {
    if (!Files.exists(Paths.get(experiment.beamTemplateConfPath))) {
      throw new IllegalArgumentException(s"Can't locate base beam config experimentFile at ${experiment.beamTemplateConfPath}")
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

  val modeChoiceTemplateFile = Paths.get(experiment.modeChoiceTemplate).toAbsolutePath
  val baseConfig = ConfigFactory.parseFile(Paths.get(experiment.beamTemplateConfPath).toFile)
  val baseScenarioRun = ExperimentRunSandbox(projectRoot, experimentFile.getParent, experiment, ExperimentRun(experiment.baseScenario, combinations = List()), baseConfig)
  val experimentVariations = experiment.combinationsOfLevels()
  val experimentRunsWithBase = baseScenarioRun :: experimentVariations.map { run =>
    ExperimentRunSandbox(projectRoot, experimentFile.getParent, experiment, run, baseConfig)
  }

  val modeChoiceTemplate = Resources.toString(modeChoiceTemplateFile.toUri.toURL, Charsets.UTF_8)
  val runScriptTemplate = experiment.getRunScriptTemplate()
  val batchScriptTemplate = experiment.getBatchRunScriptTemplate()
  val jinjava = new Jinjava()

  experimentRunsWithBase.foreach { runSandbox =>
    if (!Files.exists(runSandbox.beamConfPath.getParent)) {
      runSandbox.beamConfPath.getParent.toFile.mkdirs()
    }

    val beamConfWriter = new BufferedWriter(new FileWriter(runSandbox.beamConfPath.toFile, false))
    try {
      val beamConfStr = runSandbox.runConfig.root().render(ConfigRenderOptions.concise().setJson(false).setFormatted(true))
      beamConfWriter.write(beamConfStr)
      beamConfWriter.flush()
    } finally {
      IOUtils.closeQuietly(beamConfWriter)
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
      IOUtils.closeQuietly(modeChoiceWriter)
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
      IOUtils.closeQuietly(runScriptWriter)
    }

    Runtime.getRuntime.exec(s"chmod +x ${runSandbox.runExperimentScriptPath.toFile.toString}")


  }
  /*
   * Write a shell script designed to run the batch locally
   */
  val templateParams = Map(
    "EXPERIMENT_PATH" -> baseScenarioRun.batchRunScriptPath.getParent.toString,
  ) ++ baseScenarioRun.experimentRun.params
  val batchRunWriter = new BufferedWriter(new FileWriter(baseScenarioRun.batchRunScriptPath.toFile, false))
  try {
    val renderedTemplate = jinjava.render(batchScriptTemplate, templateParams.asJava)
    batchRunWriter.write(renderedTemplate)
    batchRunWriter.flush()
  } finally {
    IOUtils.closeQuietly(batchRunWriter)
  }
  Runtime.getRuntime.exec(s"chmod +x ${baseScenarioRun.batchRunScriptPath.toFile.toString}")

  val dynamicParamsPerFactor = experiment.getDynamicParamNamesPerFactor()
  val experimentsCsv = new BufferedWriter(new FileWriter(
    Paths.get(baseScenarioRun.experimentBaseDir.toString, "experiments.csv").toFile, false))

  try {
    val header = dynamicParamsPerFactor.map { case (factor, param_name) => s"$param_name" }.mkString(",")
    val paramNames = dynamicParamsPerFactor.map(_._2)
    experimentsCsv.write(List("experiment_name", header, "config_path").mkString("", ",", "\n"))
    experimentRunsWithBase.foreach { run =>
      val runValues = paramNames.map(run.experimentRun.getParam).mkString(",")
      val row = List(run.experimentRun.name, run.runExperimentScriptPath.getParent.toString, runValues).mkString("", ",", "\n")
      experimentsCsv.write(row)
    }
    experimentsCsv.flush()
  } finally {
    IOUtils.closeQuietly(experimentsCsv)
  }
}
