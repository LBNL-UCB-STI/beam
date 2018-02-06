package beam.agentsim.agents.choice.logit

import beam.agentsim.agents.choice.logit.UtilityParam.{Intercept, Multiplier, UtilityParamType}
import org.supercsv.cellprocessor.constraint.NotNull
import org.supercsv.cellprocessor.{Optional, ParseDouble}

import scala.beans.BeanProperty
import scala.util.Random

/**
  * BEAM
  */
case class MultinomialLogit(alternativeParams: Map[String,AlternativeParams]) {

  def sampleAlternative(alternatives: Vector[AlternativeAttributes], random: Random): String = {
    val expV = alternatives.map(alt => Math.exp(getUtilityOfAlternative(alt)))
    val sumExpV = expV.sum
    val cumulProbs = expV.map(_ / sumExpV).scanLeft(0.0)(_ + _).zipWithIndex
    val randDraw = random.nextDouble()
    val chosenIdx = (for(prob <- cumulProbs if prob._1 > randDraw)yield prob._2).head
    alternatives(chosenIdx).alternativeName
  }
  def getUtilityOfAlternative(alternative: AlternativeAttributes): Double = {
    if(!alternativeParams.contains(alternative.alternativeName)){
      Double.NaN
    }else{
      (alternativeParams("COMMON").params ++ alternativeParams(alternative.alternativeName).params).map{ theParam =>
        if(alternative.attributes.contains(theParam._1)){
          theParam._2.paramType match {
            case Multiplier =>
              theParam._2.paramValue * alternative.attributes(theParam._1)
          }
        }else if(theParam._1.equalsIgnoreCase("intercept")){
          theParam._2.paramValue
        }else{
          Double.NaN
        }
      }.toVector.sum
    }
  }
  def getExpectedMaximumUtility(alternatives: Vector[AlternativeAttributes]): Double = {
    Math.log(alternatives.map(alt => Math.exp(getUtilityOfAlternative(alt))).sum)
  }
}
object MultinomialLogit{
  def apply(theData: Vector[MnlData]): MultinomialLogit = {
    val theParams = theData.groupBy(_.alternative).map{ mnlData =>
      (mnlData._1 -> mnlData._2.map{ paramData =>
        UtilityParam(paramData.paramName,paramData.paramValue, UtilityParam.StringToUtilityParamType(paramData.paramType))
      })
    }
    MultinomialLogit(theParams.map{ case (altName, utilParams) =>
      (altName -> AlternativeParams(altName, utilParams.map( utilParam => (utilParam.paramName -> utilParam) ).toMap))
    }.toMap)
  }

  class MnlData(
      @BeanProperty var alternative: String = "COMMON",
      @BeanProperty var paramName: String = "",
      @BeanProperty var paramType: String = "",
      @BeanProperty var paramValue: Double = Double.NaN
      ) extends Cloneable {
    override def clone(): AnyRef = new MnlData(alternative, paramName, paramType, paramValue)
  }
  import org.supercsv.cellprocessor.ift.CellProcessor

  private def getProcessors = {
    Array[CellProcessor](
      new NotNull, // alt
      new NotNull, // name
      new NotNull, // type
      new Optional(new ParseDouble()) // value
    )
  }
}

// Params for model
case class AlternativeParams(alternativeName: String, params: Map[String,UtilityParam])
case class UtilityParam(paramName: String, paramValue: Double, paramType: UtilityParamType)

// Alternative attributes
case class AlternativeAttributes(alternativeName: String, attributes: Map[String,Double])

object UtilityParam {
  sealed trait UtilityParamType
  case object Intercept extends UtilityParamType
  case object Multiplier extends UtilityParamType
  def StringToUtilityParamType(str: String): UtilityParamType = {
    str.toLowerCase match {
      case "intercept" =>
        Intercept
      case "multiplier" =>
        Multiplier
      case _ =>
        throw new RuntimeException(s"Unknown Utility Parameter Type ${str}")
    }
  }
}