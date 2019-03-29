package beam.agentsim.agents.choice.logit

import beam.agentsim.agents.choice.logit.UtilityFunctionParamType.{Intercept, Multiplier}
import com.typesafe.scalalogging.LazyLogging

import scala.util.Random

/**
  * General implementation of a MultinomialLogit model
  *
  * @param utilityFunctionParams map contains all utility functions for all options that should be evaluated
  * @param common
  * @tparam A
  * @tparam T
  */
class MultinomialLogit[A, T](
  val utilityFunctionParams: Map[A, Set[UtilityFunctionParam[T]]],
  val commonUtility: Option[UtilityFunction[A, T]] = None
) extends LazyLogging {

  /**
    * Sample over a set of [[Alternative]]s by calculating the probabilities of each alternative
    * and then draw one randomly.
    *
    * For details see page 103, formula 5.8 in
    * Ben-Akiva, M., & Lerman, S. R. (1994). Discrete choice analysis : theory and application to travel demand. 6th print. Cambridge (Mass.): MIT press.
    *
    * @param alternatives the alternatives that should be sampled
    * @param random a random we can sample on
    * @return
    */
  def sampleAlternative(
    alternatives: Vector[Alternative[T, A]],
    random: Random
  ): Option[Alternative[T, A]] = {
    if (alternatives.isEmpty)
      return None

    val expV = alternatives.map(alt => Math.exp(getUtilityOfAlternative(alt)))
    // If any is +Inf then choose that as the certain alternative
    val indsOfPosInf = for (theExpV <- expV.zipWithIndex if theExpV._1 == Double.PositiveInfinity)
      yield theExpV._2
    if (indsOfPosInf.nonEmpty) {
      // Take the first
      Some(alternatives(indsOfPosInf.head))
    } else {
      val sumExpV = expV.sum
      val cumProb = expV.map(_ / sumExpV).scanLeft(0.0)(_ + _).zipWithIndex
      val randDraw = random.nextDouble()
      val idxAboveDraw = for (prob <- cumProb if prob._1 > randDraw) yield prob._2
      if (idxAboveDraw.isEmpty) {
        None
      } else {
        val chosenIdx = idxAboveDraw.head - 1
        Some(alternatives(chosenIdx))
      }
    }
  }

  /**
    * Get the expected maximum utility over a set of [[Alternative]]s
    *
    * @param alternatives the alternatives that should be evaluated
    * @return
    */
  def getExpectedMaximumUtility(alternatives: Vector[Alternative[T, A]]): Double = {
    Math.log(alternatives.map(alt => Math.exp(getUtilityOfAlternative(alt))).sum)
  }

  /**
    * Calculate the utility of the provided alternative based on the utility functions provided during the initialization of
    * the MultinomialLogit model. If the provided utility functions are not able to evaluate the provided alternative
    * (e.g. there is no function for the provided alternative) the provided utility is -1E100
    *
    * @param alternative the alternative to evaluate
    * @return
    */
  def getUtilityOfAlternative(alternative: Alternative[T, A]): Double = {

    // if we have commonParams provided we wanna use them
    val commonParams = commonUtility match {
      case Some(x) => x.params
      case None    => Set()
    }

    val evaluated: Iterable[Double] = for {
      theseParams                                         <- utilityFunctionParams.get(alternative.alternativeId).toList
      UtilityFunctionParam(param, paramType, coefficient) <- theseParams ++ commonParams
    } yield {
      val thisParam: Double = alternative.attributes.get(param).getOrElse(0)
      paramType.op(coefficient, thisParam)
    }
    if (evaluated.isEmpty) -1E100 else evaluated.sum
  }
}

object MultinomialLogit {

  def apply[A, T](utilityFunctionParams: Map[A, Set[UtilityFunctionParam[T]]]): MultinomialLogit[A, T] = {
    new MultinomialLogit(utilityFunctionParams)
  }

  def apply[A, T](utilityFunctionData: IndexedSeq[UtilityFunction[A, T]]): MultinomialLogit[A, T] = {
    new MultinomialLogit(reduceInputData(utilityFunctionData))
  }

  def apply[A, T](
    utilityFunctionData: IndexedSeq[UtilityFunction[A, T]],
    commonUtility: Option[UtilityFunction[A, T]]
  ): MultinomialLogit[A, T] = {
    new MultinomialLogit(reduceInputData(utilityFunctionData), commonUtility)
  }

  /**
    * Reduce the provided input data to ensure that we have unique [[UtilityFunctionParam]]s for each utility function
    *
    * @param utilityFunctionData the provided utility functions that should be reduced
    * @tparam A
    * @tparam T
    * @return
    */
  private def reduceInputData[A, T](
    utilityFunctionData: IndexedSeq[UtilityFunction[A, T]]
  ): Map[A, Set[UtilityFunctionParam[T]]] = {
    utilityFunctionData.groupBy(_.alternativeId).map { data =>
      data._1 -> data._2.flatMap { utilityFunction =>
        utilityFunction.params
      }.toSet
    }
  }
}
