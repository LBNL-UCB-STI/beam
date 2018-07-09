package beam.experiment

import java.io._
import java.nio.file.{Files, Paths}

import com.google.common.base.Charsets
import com.google.common.io.Resources
import com.hubspot.jinjava.Jinjava
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}
import org.apache.commons.io.IOUtils
import org.apache.commons.lang.SystemUtils
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
    if (!Files.exists(Paths.get(experiment.header.beamTemplateConfPath))) {
      throw new IllegalArgumentException(s"Can't locate base beam config experimentFile at ${experiment.header.beamTemplateConfPath}")
    }

    val modeChoiceTemplateFile = Paths.get(experiment.header.modeChoiceTemplate).toAbsolutePath
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

  def getExperimentPath = {
    Paths.get(experimentFile.getParent.toString, "runs")
  }

  def getBatchRunScriptPath = {
    Paths.get(getExperimentPath.toString, "batchRunExperiment.sh")
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
    throw new IllegalArgumentException(s"$ExperimentsParamName file is missing: $experimentFile")
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

  val baseConfig = ConfigFactory.parseFile(Paths.get(experiment.header.beamTemplateConfPath).toFile)
  val experimentVariations = experiment.combinationsOfLevels()
  val experimentRuns = experimentVariations.map { run =>
    ExperimentRunSandbox(projectRoot, experimentFile.getParent, experiment, run, baseConfig)
  }

  val modeChoiceTemplate = Resources.toString(Paths.get(experiment.header.modeChoiceTemplate).toAbsolutePath.toUri.toURL, Charsets.UTF_8)
  val runScriptTemplate = experiment.getRunScriptTemplate
  val batchScriptTemplate = experiment.getBatchRunScriptTemplate
  val jinjava = new Jinjava()

  experimentRuns.foreach { runSandbox =>
    val templateParams = Map(
      "BEAM_CONFIG_PATH" -> runSandbox.beamConfPath.toString,
      "BEAM_OUTPUT_PATH" -> runSandbox.beamOutputDir.toString
      //      ,
      //      "BEAM_SHARED_INPUT" -> runSandbox.beamOutputDir.toString

    ) ++ experiment.header.params.asScala ++ runSandbox.experimentRun.params

    /*
     * Write the config file
     */
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

    /*
     * Write the shell script to run the single model run
     */
    if (!Files.exists(runSandbox.runBeamScriptPath.getParent)) {
      runSandbox.runBeamScriptPath.getParent.toFile.mkdirs()
    }
    val runScriptWriter = new BufferedWriter(new FileWriter(runSandbox.runBeamScriptPath.toFile, false))
    try {
      val renderedTemplate = jinjava.render(runScriptTemplate, templateParams.asJava)
      runScriptWriter.write(renderedTemplate)
      runScriptWriter.flush()
    } finally {
      IOUtils.closeQuietly(runScriptWriter)
    }
    if (!SystemUtils.IS_OS_WINDOWS) {
      Runtime.getRuntime.exec(s"chmod +x ${runSandbox.runBeamScriptPath.toFile.toString}")
    }
    /*
     * Optionally write the mode choice params file
     */
    experiment.header.modeChoiceTemplate.toString match {
      case "" =>
        // Do nothing since modeChoieParams wasn't specified in experiment.yaml file
        ""
      case uri =>
        if (!Files.exists(runSandbox.modeChoiceParametersXmlPath.getParent)) {
          runSandbox.modeChoiceParametersXmlPath.getParent.toFile.mkdirs()
        }
        val modeChoiceWriter = new BufferedWriter(new FileWriter(runSandbox.modeChoiceParametersXmlPath.toFile, false))
        try {
          val renderedTemplate = jinjava.render(modeChoiceTemplate, templateParams.asJava)
          modeChoiceWriter.write(renderedTemplate)
          modeChoiceWriter.flush()
        } finally {
          IOUtils.closeQuietly(modeChoiceWriter)
        }
    }
  }
  /*
   * Write a shell script designed to run the batch locally
   */
  val templateParams = Map(
    "EXPERIMENT_PATH" -> getExperimentPath.toString,
  ) ++ experiment.defaultParams.asScala
  val batchRunWriter = new BufferedWriter(new FileWriter(getBatchRunScriptPath.toFile, false))
  try {
    val renderedTemplate = jinjava.render(batchScriptTemplate, templateParams.asJava)
    batchRunWriter.write(renderedTemplate)
    batchRunWriter.flush()
  } finally {
    IOUtils.closeQuietly(batchRunWriter)
  }
  if (!SystemUtils.IS_OS_WINDOWS) {
    Runtime.getRuntime.exec(s"chmod +x ${getBatchRunScriptPath.toString}")
  }

  val dynamicParamsPerFactor = experiment.getDynamicParamNamesPerFactor
  val experimentsCsv = new BufferedWriter(new FileWriter(
    Paths.get(getExperimentPath.toString,"experiments.csv").toFile, false))

  try {
    val factorNames: List[String] = dynamicParamsPerFactor.map(_._1)
    val paramNames: List[String] = dynamicParamsPerFactor.map(_._2)
    val header = (List("experimentalGroup") ++ factorNames ++ paramNames).mkString("",",","\n")
    experimentsCsv.write(header)
    experimentRuns.foreach { run =>
      val levelNames = factorNames.map(run.experimentRun.getLevelTitle).mkString(",")
      val runValues = paramNames.map(run.experimentRun.getParam).mkString(",")
      val row = List(run.experimentRun.name, levelNames, runValues).mkString("", ",", "\n")
      experimentsCsv.write(row)
    }
    experimentsCsv.flush()
  } finally {
    IOUtils.closeQuietly(experimentsCsv)
  }
}
