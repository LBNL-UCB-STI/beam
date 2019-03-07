package beam.agentsim.agents.choice.logit
import beam.agentsim.agents.choice.logit.UtilityParamType.Multiplier

import scala.beans.BeanProperty

//class MnlData(
//  @BeanProperty var alternative: String = "COMMON",
//  @BeanProperty var paramName: String = "",
//  @BeanProperty var paramType: String = "",
//  @BeanProperty var paramValue: Double = Double.NaN
//) extends Cloneable {
//  override def clone(): AnyRef = new MnlData(alternative, paramName, paramType, paramValue)
//}



// todo remove as this is only one row in the AlternativeAttributes class
/**
  * The parameters for each alternative that should be evaluated in the [[MultinomialLogit]] model
  *
  * @param alternativeId the general identifier of the alternative that should be evaluated (e.g. car)
  * @param paramName the parameter identifier for the alternative (e.g. costs)
  * @param paramType the type of the parameter as of [[UtilityParamType]] (e.g. multiplier)
  * @param paramValue the value of the param type (e.g. -1.0)
  * @tparam T
  */
case class MnlData[T](
  alternativeId: T = "COMMON",
  utilityParam: UtilityParam[T]
) extends Cloneable {

  def this(
    @BeanProperty alternativeId: T,
    @BeanProperty paramName: T,
    @BeanProperty paramType: String,
    @BeanProperty paramValue: Double
  ) =  {
    this(alternativeId, UtilityParam(paramName, UtilityParamType(paramType), paramValue))
  }

  override def clone(): AnyRef = new MnlData(alternativeId, utilityParam)

}
