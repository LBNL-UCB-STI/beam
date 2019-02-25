package beam.agentsim.agents.choice.logit

import beam.agentsim.agents.choice.logit.UtilityParam.{Intercept, Multiplier, UtilityParamType}
import com.typesafe.scalalogging.LazyLogging
import org.supercsv.cellprocessor.constraint.NotNull
import org.supercsv.cellprocessor.ift.CellProcessor
import org.supercsv.cellprocessor.{Optional, ParseDouble}

import scala.beans.BeanProperty
import scala.util.Random

/**
  * BEAM
  */
case class MultinomialLogit(alternativeParams: Map[String, AlternativeParams]) extends LazyLogging {

  def sampleAlternative(
    alternatives: Vector[AlternativeAttributes],
    random: Random
  ): Option[String] = {
    val expV = alternatives.map(alt => Math.exp(getUtilityOfAlternative(alt)))
    // If any is +Inf then choose that as the certain alternative
    val indsOfPosInf = for (theExpV <- expV.zipWithIndex if theExpV._1 == Double.PositiveInfinity)
      yield theExpV._2
    if (indsOfPosInf.nonEmpty) {
      // Take the first
      Some(alternatives(indsOfPosInf.head).alternativeName)
    } else {
      val sumExpV = expV.sum
      val cumulProbs = expV.map(_ / sumExpV).scanLeft(0.0)(_ + _).zipWithIndex
      val randDraw = random.nextDouble()
      val idxAboveDraw = for (prob <- cumulProbs if prob._1 > randDraw) yield prob._2
      if (idxAboveDraw.isEmpty) {
        None
      } else {
        val chosenIdx = idxAboveDraw.head - 1
        Some(alternatives(chosenIdx).alternativeName)
      }
    }
  }

  def getExpectedMaximumUtility(alternatives: Vector[AlternativeAttributes]): Double = {
    Math.log(alternatives.map(alt => Math.exp(getUtilityOfAlternative(alt))).sum)
  }

  def getUtilityOfAlternative(alternative: AlternativeAttributes): Double = {
    if (!alternativeParams.contains(alternative.alternativeName)) {
      -1E100
    } else {
      (alternativeParams.getOrElse("COMMON", AlternativeParams.empty).params ++ alternativeParams(
        alternative.alternativeName
      ).params)
        .map { theParam =>
          if (alternative.attributes.contains(theParam._1)) {
            theParam._2.paramType match {
              case Multiplier =>
                (theParam._2.paramValue * alternative.attributes(theParam._1)).toDouble
              case Intercept =>
                theParam._2.paramValue.toDouble
            }
          } else if (theParam._1.equalsIgnoreCase("intercept") || theParam._1.equalsIgnoreCase(
                       "asc"
                     )) {
            theParam._2.paramValue.toDouble
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

  def apply(theData: IndexedSeq[MnlData]): MultinomialLogit = {
    val theParams = theData.groupBy(_.alternative).map { mnlData =>
      mnlData._1 -> mnlData._2.map { paramData =>
        UtilityParam(
          paramData.paramName,
          paramData.paramValue,
          UtilityParam.StringToUtilityParamType(paramData.paramType)
        )
      }
    }
    MultinomialLogit(theParams.map {
      case (altName, utilParams) =>
        altName -> AlternativeParams(
          altName,
          utilParams.map(utilParam => utilParam.paramName -> utilParam).toMap
        )
    })
  }

  /*private def getProcessors = {
    Array[CellProcessor](
      new NotNull, // alt
      new NotNull, // name
      new NotNull, // type
      new Optional(new ParseDouble()) // value
    )
  }*/

  class MnlData(
    @BeanProperty var alternative: String = "COMMON",
    @BeanProperty var paramName: String = "",
    @BeanProperty var paramType: String = "",
    @BeanProperty var paramValue: Double = Double.NaN
  ) extends Cloneable {
    override def clone(): AnyRef = new MnlData(alternative, paramName, paramType, paramValue)
  }
}

// Params for model
case class AlternativeParams(alternativeName: String, params: Map[String, UtilityParam])

object AlternativeParams {
  def empty: AlternativeParams = AlternativeParams("", Map())
}

case class UtilityParam(paramName: String, paramValue: Double, paramType: UtilityParamType)

// Alternative attributes
case class AlternativeAttributes(alternativeName: String, attributes: Map[String, Double])

object UtilityParam {

  def StringToUtilityParamType(str: String): UtilityParamType = {
    str.toLowerCase match {
      case "intercept" =>
        Intercept
      case "multiplier" =>
        Multiplier
      case _ =>
        throw new RuntimeException(s"Unknown Utility Parameter Type $str")
    }
  }

  sealed trait UtilityParamType

  case object Intercept extends UtilityParamType

  case object Multiplier extends UtilityParamType
}
