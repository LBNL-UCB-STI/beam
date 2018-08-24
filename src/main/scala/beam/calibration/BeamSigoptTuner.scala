package beam.calibration

import java.io.File
import java.nio.file.{Files, Path, Paths}

import beam.experiment._
import com.google.common.collect.Lists
import com.sigopt.Sigopt
import com.sigopt.exception.SigoptException
import com.sigopt.model._
import com.typesafe.config.{Config, ConfigFactory}

import scala.collection.JavaConverters

case class SigoptExperimentData(
  experimentDef: ExperimentDef,
  experimentPath: File,
  benchmarkFileLoc: String,
  development: Boolean = false
) {

  lazy val projectRoot: Path = {
    if (System.getenv("BEAM_ROOT") != null) {
      Paths.get(System.getenv("BEAM_ROOT"))
    } else {
      Paths.get("./").toAbsolutePath.getParent
    }
  }

  val baseConfig: Config =
    ConfigFactory.parseFile(Paths.get(experimentDef.getHeader.getBeamTemplateConfPath).toFile)

  val experiment: Experiment = BeamSigoptTuner.createOrFetchExperiment(experimentDef, development)

  val isParallel: Boolean = experimentDef.header.isParallel

}

object SigoptExperimentData {

  def apply(
    experimentLoc: String,
    benchmarkFileLoc: String,
    development: Boolean
  ): SigoptExperimentData = {

    val experimentPath: Path = new File(experimentLoc).toPath.toAbsolutePath

    if (!Files.exists(experimentPath)) {
      throw new IllegalArgumentException(s"Experiments file is missing: $experimentPath")
    }

    SigoptExperimentData(
      BeamSigoptTuner.loadExperimentDef(experimentPath.toFile),
      experimentPath.toFile,
      benchmarkFileLoc,
      development
    )
  }
}

object BeamSigoptTuner {

  import Bounded._

  // Fields //

  /**
    * Creates a new Sigopt [[Experiment]] based on the [[ExperimentDef]] model or else
    * fetches it from the online database.
    *
    * @return The fully instantiated [[Experiment]].
    * @throws SigoptException If the experiment cannot be created, this exception is thrown.
    */
  @throws[SigoptException]
  def createOrFetchExperiment(
    experimentDef: ExperimentDef,
    development: Boolean = false
  ): Experiment = {
    val client = new Client(Sigopt.clientToken)
    val header = experimentDef.getHeader
    val experimentId = header.getTitle
    val experimentList = Experiment.list().call().getData
    val optExperiment = experimentList.stream
      .filter(
        (experiment: Experiment) =>
          experiment.getName == experimentId & experiment.getDevelopment == development
      )
      .findFirst
    optExperiment.orElse(createExperiment(experimentDef))
  }

  @throws[SigoptException]
  private def createExperiment(implicit experimentDef: ExperimentDef): Experiment = {
    val header = experimentDef.getHeader
    val experimentId = header.getTitle
    val factors = JavaConverters.asScalaIterator(experimentDef.getFactors.iterator()).seq
    val parameters =
      Lists.newArrayList(JavaConverters.asJavaIterator(factors.flatMap(factorToParameters)))
    Experiment.create
      .data(new Experiment.Builder().name(experimentId).parameters(parameters).build)
      .call

  }

  def loadExperimentDef(experimentFileLoc: File): ExperimentDef = {
    val experiment = ExperimentGenerator.loadExperimentDefs(experimentFileLoc)
    ExperimentGenerator.validateExperimentConfig(experiment)

    experiment
  }

  /**
    * Converts a [[Factor factor]] to a [[List]] of SigOpt [[Parameter parameters]]
    * assuming that there are [[Level levels]] with High and Low names.
    *
    * The type of the parameter values is equivalent to the first key of the `High`
    * [[Level level]] `param`.
    *
    * @param factor [[Factor]] to convert
    * @return The factor as a [[List]] of SigOpt [[Parameter parameters]]
    */
  def factorToParameters(factor: Factor): List[Parameter] = {

    val levels = factor.levels

    val highLevel = getLevel("High", levels)

    val paramNames: Vector[String] =
      JavaConverters.asScalaIterator(highLevel.params.keySet().iterator()).toVector

    paramNames.map { paramName =>
      val maxValue = highLevel.params.get(paramName)
      val lowLevel = getLevel("Low", levels)
      val minValue = lowLevel.params.get(paramName)

      val parameter = new Parameter.Builder().name(paramName)

      // Build bounds
      maxValue match {
        case _: Double =>
          parameter
            .bounds(getBounds(minValue.asInstanceOf[Double], maxValue.asInstanceOf[Double]))
            .`type`("double")
        case _: Int =>
          parameter
            .`type`("int")
            .bounds(getBounds(minValue.asInstanceOf[Int], maxValue.asInstanceOf[Int]))
        case _ =>
          throw new RuntimeException("Type error!")
      }
      parameter.build()
    }.toList

  }

  private def getLevel(levelName: String, levels: java.util.List[Level]): Level =
    JavaConverters
      .collectionAsScalaIterable(levels)
      .find(l => {
        l.name.equals(levelName)
      })
      .orNull

}
