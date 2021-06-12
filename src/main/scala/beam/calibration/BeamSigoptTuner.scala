package beam.calibration

import java.nio.file.Paths

import beam.calibration.BeamSigoptTuner._
import beam.calibration.Bounded._
import beam.experiment._
import beam.utils.OptionalUtils.JavaOptionals._
import com.google.common.collect.Lists
import com.sigopt.Sigopt
import com.sigopt.exception.SigoptException
import com.sigopt.model._
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging

import scala.collection.JavaConverters
import scala.util.Try

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
          experiment.getId == experimentId && experiment.getDevelopment.booleanValue() == development
      )
      .findFirst
    optExperiment.toOption
  }

  @throws[SigoptException]
  def createExperiment(implicit experimentDef: ExperimentDef): Experiment = {
    val header = experimentDef.getHeader
    val experimentId = header.getTitle
    val factors = JavaConverters.asScalaIterator(experimentDef.factors.iterator()).seq
    val parameters =
      Lists.newArrayList(JavaConverters.asJavaIterator(factors.flatMap(factorToParameters)))
    val experiment: Experiment = new Experiment.Builder().name(experimentId).parameters(parameters).build
    val expCall = Experiment.create.data(experiment)
    expCall.call()
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

      //println(maxValue)

      // Build bounds
      maxValue match {
        case x if x.isInstanceOf[Double] =>
          parameter
            .bounds(getBounds(minValue.asInstanceOf[Double], maxValue.asInstanceOf[Double]))
            .`type`("double")
        case x if x.isInstanceOf[Int] =>
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
