package beam.calibration

import java.io.File
import java.nio.file.{Files, Path, Paths}

import Bounded._
import beam.utils.OptionalUtils.JavaOptionals._
import beam.experiment._
import com.google.common.collect.Lists
import com.sigopt.Sigopt
import com.sigopt.exception.SigoptException
import com.sigopt.model._
import com.typesafe.config.{Config, ConfigFactory}

import scala.collection.JavaConverters
import scala.util.Try
import beam.calibration.BeamSigoptTuner._
import com.typesafe.scalalogging.LazyLogging

case class SigoptExperimentData(
  experimentDef: ExperimentDef,
  experimentPath: File,
  benchmarkFileLoc: String,
  experimentId: String,
  development: Boolean = false
) extends LazyLogging {

  lazy val projectRoot: Path = {
    if (System.getenv("BEAM_ROOT") != null) {
      Paths.get(System.getenv("BEAM_ROOT"))
    } else {
      Paths.get("./").toAbsolutePath.getParent
    }
  }

  val baseConfig: Config =
    ConfigFactory.parseFile(Paths.get(experimentDef.getHeader.getBeamTemplateConfPath).toFile)

  // Always default to single JVM if incorrect entry
  val numWorkers: Int = Try { experimentDef.header.numWorkers.toInt }.getOrElse(1)

  val isParallel: Boolean = numWorkers > 1

  val isMaster: Boolean = experimentId == "None"

  val experiment: Experiment =
    fetchExperiment(experimentId) match {
      case Some(foundExperiment) =>
        logger.info(s"Retrieved the existing experiment with experiment id $experimentId")
        if(isParallel){
          Experiment.update(foundExperiment.getId).data(s"""{"parallel_bandwidth":$numWorkers}""").call()
        }
        foundExperiment
      case None =>
        val createdExperiment: Experiment = createExperiment(experimentDef)
        logger.info("New Experiment created with experimentId [" + createdExperiment.getId + "]")
        System.exit(0)
        createdExperiment
    }

}

object SigoptExperimentData {

  def apply(
    experimentLoc: String,
    benchmarkFileLoc: String,
    experimentId: String,
    development: Boolean
  ): SigoptExperimentData = {

    val experimentPath: Path = new File(experimentLoc).toPath.toAbsolutePath

    if (!Files.exists(experimentPath)) {
      throw new IllegalArgumentException(s"Experiments file is missing: $experimentPath")
    }

    // If experiment ID is missing, create one

    SigoptExperimentData(
      loadExperimentDef(experimentPath.toFile),
      experimentPath.toFile,
      benchmarkFileLoc,
      experimentId,
      development
    )
  }
}

object BeamSigoptTuner {

  val client = new Client(Sigopt.clientToken)

  // Fields //

  /**
    * Creates a new Sigopt [[Experiment]] based on the [[ExperimentDef]] model or else
    * fetches it from the online database.
    *
    * @return The fully instantiated [[Experiment]].
    * @throws SigoptException If the experiment cannot be created, this exception is thrown.
    */
  @throws[SigoptException]
  def fetchExperiment(
    experimentId: String,
    development: Boolean = false
  ): Option[Experiment] = {

    val experimentList = Experiment.list().call().getData
    val optExperiment = experimentList.stream
      .filter(
        (experiment: Experiment) =>
          experiment.getId == experimentId & experiment.getDevelopment == development
      )
      .findFirst
    optExperiment.toOption
  }

  @throws[SigoptException]
  def createExperiment(implicit experimentDef: ExperimentDef): Experiment = {
    val header = experimentDef.getHeader
    val experimentId = header.getTitle
    val factors = JavaConverters.asScalaIterator(experimentDef.getFactors.iterator()).seq
    val parameters =
      Lists.newArrayList(JavaConverters.asJavaIterator(factors.flatMap(factorToParameters)))
    val experiment: Experiment = new Experiment.Builder().name(experimentId).parameters(parameters).build
    val expCall = Experiment.create.data(experiment)
    expCall.call()
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
