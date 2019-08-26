package beam.agentsim.agents.choice.logit

import scala.util.Random

import org.scalatest.WordSpec

class MultinomialLogitTest extends WordSpec {

  "MultinomialLogitTest" when {
    "getUtilityOfAlternative" should {
      "correctly compute value of mock alternatives" in new MultinomialLogitTest.SimpleSetOfAlternatives {
        val random: Random = Random
        val mnl: MultinomialLogit[AlternativeType, AltParams] = MultinomialLogit(
          Map(
            AlternativeType.Alt1 -> Map(
              AltParams.Param1 -> UtilityFunctionOperation.Multiplier(1.0)
            )
          )
        )
        for {
          value <- List(-1.0, -0.5, 0.0, 0.5, 1.0)
        } {
          val alternatives: Map[AlternativeType, Map[AltParams, Double]] = Map(
            AlternativeType.Alt1 -> Map(
              AltParams.Param1 -> value
            )
          )
          val result = mnl.sampleAlternative(alternatives, random)
          println(s"value $value with result $result")
        }
      }
    }

  }
}

object MultinomialLogitTest {

  trait SimpleSetOfAlternatives {
    sealed trait AlternativeType
    object AlternativeType {
      case object Alt1 extends AlternativeType
      case object Alt2 extends AlternativeType
    }

    sealed trait AltParams
    object AltParams {
      case object Param1 extends AltParams
      case object Param2 extends AltParams
    }
  }
}

