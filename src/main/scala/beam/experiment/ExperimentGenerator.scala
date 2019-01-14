package beam.experiment

import java.io._
import java.nio.file.{Files, Path, Paths}

import com.google.common.base.Charsets
import com.google.common.io.Resources
import com.hubspot.jinjava.Jinjava
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}
import org.apache.commons.io.IOUtils
import org.apache.commons.lang.SystemUtils

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
object ExperimentGenerator extends ExperimentApp {
  import beam.experiment.ExperimentApp

  override def validateExperimentConfig(experiment: ExperimentDef): Unit = {
    if (!Files.exists(Paths.get(experiment.header.beamTemplateConfPath))) {
      throw new IllegalArgumentException(
        s"Can't locate base beam config experimentFile at ${experiment.header.beamTemplateConfPath}"
      )
    }

    val modeChoiceTemplateFile = Paths.get(experiment.header.modeChoiceTemplate).toAbsolutePath
    if (!Files.exists(modeChoiceTemplateFile)) {
      throw new IllegalArgumentException(
        "No mode choice template found " + modeChoiceTemplateFile.toString
      )
    }
  }

  def getExperimentPath(): Path = {
    Paths.get(experimentPath.getParent.toString, "runs")
  }

  def getBatchRunScriptPath = {
    Paths.get(getExperimentPath.toString, "batchRunExperiment.sh")
  }

  val baseConfig = ConfigFactory.parseFile(Paths.get(experimentDef.header.beamTemplateConfPath).toFile)
  val experimentVariations = experimentDef.combinationsOfLevels()

  val experimentRuns = experimentVariations.map { run =>
    ExperimentRunSandbox(experimentPath.getParent, experimentDef, run, baseConfig)
  }

  val modeChoiceTemplate = Resources.toString(
    Paths.get(experimentDef.header.modeChoiceTemplate).toAbsolutePath.toUri.toURL,
    Charsets.UTF_8
  )
  val runScriptTemplate = experimentDef.getRunScriptTemplate
  val batchScriptTemplate = experimentDef.getBatchRunScriptTemplate
  val jinjava = new Jinjava()

  experimentRuns.foreach { runSandbox =>
    val templateParams = Map(
      "BEAM_CONFIG_PATH" -> runSandbox.beamConfPath.toString,
      "BEAM_OUTPUT_PATH" -> runSandbox.beamOutputDir.toString
      //      ,
      //      "BEAM_SHARED_INPUT" -> runSandbox.beamOutputDir.toString
    ) ++ experimentDef.header.deployParams.asScala ++ runSandbox.experimentRun.params

    /*
     * Write the config file
     */
    if (!Files.exists(runSandbox.beamConfPath.getParent)) {
      runSandbox.beamConfPath.getParent.toFile.mkdirs()
    }
    val beamConfWriter = new BufferedWriter(new FileWriter(runSandbox.beamConfPath.toFile, false))
    try {
      val beamConfStr = runSandbox.runConfig
        .root()
        .render(ConfigRenderOptions.concise().setJson(false).setFormatted(true))
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
    val runScriptWriter =
      new BufferedWriter(new FileWriter(runSandbox.runBeamScriptPath.toFile, false))
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
    experimentDef.header.modeChoiceTemplate.toString match {
      case "" =>
        // Do nothing since modeChoiceParams wasn't specified in experiment.yaml file
        ""
      case uri =>
        if (!Files.exists(runSandbox.modeChoiceParametersXmlPath.getParent)) {
          runSandbox.modeChoiceParametersXmlPath.getParent.toFile.mkdirs()
        }
        val modeChoiceWriter =
          new BufferedWriter(new FileWriter(runSandbox.modeChoiceParametersXmlPath.toFile, false))
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
    "EXPERIMENT_PATH" -> getExperimentPath().toString,
  ) ++ experimentDef.defaultParams.asScala
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

  val dynamicParamsPerFactor = experimentDef.getDynamicParamNamesPerFactor

  val experimentsCsv = new BufferedWriter(
    new FileWriter(Paths.get(getExperimentPath().toString, "experiments.csv").toFile, false)
  )

  try {
    val factorNames: List[String] = dynamicParamsPerFactor.map(_._1)
    val paramNames: List[String] = dynamicParamsPerFactor.map(_._2)
    val header = (List("experimentalGroup") ++ factorNames ++ paramNames).mkString("", ",", "\n")
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
