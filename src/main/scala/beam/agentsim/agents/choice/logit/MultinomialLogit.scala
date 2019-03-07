package beam.agentsim.agents.choice.logit

import beam.agentsim.agents.choice.logit.UtilityParamType.{Intercept, Multiplier}
import com.typesafe.scalalogging.LazyLogging

import scala.util.Random

/**
  * BEAM
  */
case class MultinomialLogit[A, T](utilityFunctionParams: Map[A, List[UtilityParam[T]]], val common: A)
    extends LazyLogging {
//  def this(utilityFunctionData: IndexedSeq[AlternativeParams[A, T]]) = {
//    this(utilityFunctionData.map(data => data.alternativeId -> data.params).toMap)
//  }

//  def sampleAlternative(
//    alternatives: Vector[AlternativeAttributes],
//    random: Random
//  ): Option[String] = {
//    val expV = alternatives.map(alt => Math.exp(getUtilityOfAlternative(alt)))
//    // If any is +Inf then choose that as the certain alternative
//    val indsOfPosInf = for (theExpV <- expV.zipWithIndex if theExpV._1 == Double.PositiveInfinity)
//      yield theExpV._2
//    if (indsOfPosInf.nonEmpty) {
//      // Take the first
//      Some(alternatives(indsOfPosInf.head).alternativeName)
//    } else {
//      val sumExpV = expV.sum
//      val cumulProbs = expV.map(_ / sumExpV).scanLeft(0.0)(_ + _).zipWithIndex
//      val randDraw = random.nextDouble()
//      val idxAboveDraw = for (prob <- cumulProbs if prob._1 > randDraw) yield prob._2
//      if (idxAboveDraw.isEmpty) {
//        None
//      } else {
//        val chosenIdx = idxAboveDraw.head - 1
//        Some(alternatives(chosenIdx).alternativeName)
//      }
//    }
//  }

//  def getExpectedMaximumUtility(alternatives: Vector[AlternativeAttributes]): Double = {
//    Math.log(alternatives.map(alt => Math.exp(getUtilityOfAlternative(alt))).sum)
//  }

  def getUtilityOfAlternative(alternative: AlternativeAttributes[T, A]): Double = {

    if (!utilityFunctionParams.contains(alternative.alternativeName)) {
      -1E100
    } else {

      // get all parameters that apply to the alternative and calculate the utility based on the coefficients and their type
      (utilityFunctionParams
        .getOrElse(common, UtilityFunctionParams.empty) //todo FIX this alternative to work correctly!
        .asInstanceOf[UtilityFunctionParams[T, A]]
        .params ++ utilityFunctionParams(alternative.alternativeName))
        .map { utilityParam =>
          if (alternative.attributes.contains(utilityParam.param.asInstanceOf[T])) {
            utilityParam.paramType match {
              case Multiplier =>
                (utilityParam.paramValue * alternative.attributes(utilityParam.param.asInstanceOf[T]))
              case Intercept => utilityParam.paramValue.toDouble
              case _         => throw new IllegalArgumentException("Unknown UtilityParameterType " + utilityParam.paramType)
            }
          } else if (utilityParam.param.equals("intercept") || utilityParam.param.equals("asc")) {
            utilityParam.paramValue.toDouble
          } else {
            -1E100
          }

        }
        .toVector
        .sum
    }
  }
}

object MultinomialLogit {

  def apply[A, T](
    utilityFunctionData: IndexedSeq[UtilityFunctionParams[A, T]],
    commonId: A
  ): MultinomialLogit[A, T] = {
    MultinomialLogit(utilityFunctionData.map(data => data.alternativeId -> data.params).toMap, commonId)
  }

}
