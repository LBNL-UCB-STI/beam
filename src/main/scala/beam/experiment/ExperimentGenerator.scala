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
import scala.collection.immutable

/**
  * Generate beam.conf and run script for individual run.
  * Couple notes:
  *  1. paths n config and templates should be relative to project root
  *  2. --experiments is used to pass location of experiments.yml
  *
  * This generator will create sub-directories relatively to experiments.yml
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

  def getExperimentPath: Path = {
    Paths.get(experimentDef.header.experimentOutputRoot, "scenarios", experimentDef.getHeader.experimentId)
  }

  val baseConfig = ConfigFactory.parseFile(Paths.get(experimentDef.header.beamTemplateConfPath).toFile)
  val experimentVariations: immutable.Seq[(ExperimentRun, Int)] = experimentDef.combinationsOfLevels().zipWithIndex

  val experimentRuns = experimentVariations.map { case (run, runIdx) =>
    ExperimentRunSandbox(experimentDef, run, runIdx, baseConfig)
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
     * Write the run folder
     */
    if (!Files.exists(runSandbox.runsDirectory)) {
      runSandbox.runsDirectory.toFile.mkdirs()
    }

    /*
     * Optionally write the mode choice params file
     */
    experimentDef.header.modeChoiceTemplate match {
      case "" =>
        // Do nothing since modeChoiceParams wasn't specified in experiment.yaml file
        ""
      case _ =>
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

  val dynamicParamsPerFactor = experimentDef.getDynamicParamNamesPerFactor

  val experimentsCsv = new BufferedWriter(
    new FileWriter(Paths.get(getExperimentPath.toString, "experiments.csv").toFile, false)
  )

  try {
    val factorNames: List[String] = dynamicParamsPerFactor.map(_._1)
    val paramNames: List[String] = dynamicParamsPerFactor.map(_._2)
    val header = (List("runId") ++ factorNames ++ paramNames).mkString("", ",", "\n")
    experimentsCsv.write(header)
    experimentRuns.foreach { run =>
      val levelNames = factorNames.map(run.experimentRun.getLevelTitle).mkString(",")
      val runValues = paramNames.map(run.experimentRun.getParam).mkString(",")
      val row = List(run.experimentIdx, levelNames, runValues).mkString("", ",", "\n")
      experimentsCsv.write(row)
    }
    experimentsCsv.flush()
  } finally {
    IOUtils.closeQuietly(experimentsCsv)
  }
}
