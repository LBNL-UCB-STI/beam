package beam.agentsim.agents.choice.logit

import scala.util.Random
import org.scalatest.{Matchers, WordSpecLike}

class MultinomialLogitSpec extends WordSpecLike with Matchers {

  "An MNL Model with standard data" must {
    val utilityFunctions = Map(
      "car"  -> Map("intercept" -> UtilityFunctionOperation.Intercept(3.0)),
      "walk" -> Map("intercept" -> UtilityFunctionOperation.Intercept(4.0))
    )

    val common = Map(
      "cost" -> UtilityFunctionOperation.Multiplier(-0.01),
      "time" -> UtilityFunctionOperation.Multiplier(-0.02)
    )

    val mnl = new MultinomialLogit(utilityFunctions, common)

    val alts = Map(
      "car"  -> Map("cost" -> 30.0, "time" -> 50.0),
      "walk" -> Map("cost" -> 0.0, "time"  -> 40.0)
    )

    "should evaluate utility functions as expected" in {
      mnl.getUtilityOfAlternative(alts.head._1, alts.head._2) match {
        case None => fail()
        case Some(util) =>
          (util - 1.7).abs should be < 0.000000001
      }

    }
    "should evaluate expected max utility as expected" in {
      val util = mnl.getExpectedMaximumUtility(alts)
      Math.abs(util.get - 3.401413) < 0.00001 should be(true)
    }

    "should sample higher probability alternatives more often" in {
      // With these inputs, we expect "walk" ~81% of the time, which translates to an almost certainty that majority
      // will be walk with 100 trials (p-val 3.00491e-12)

      val sampleSize = 100
      val rand = new Random()

      val samples: Seq[String] = for {
        _      <- 1 until sampleSize
        result <- mnl.sampleAlternative(alts, rand)
      } yield result.alternativeType

      samples.count {
        _ == "walk"
      } > (sampleSize / 2) // 50% or more should be walk

    }
  }
  "An MNL Model with arbitrary data" must {
    // the alternatives as objects
    sealed trait Mode

    object Car extends Mode
    object Walk extends Mode

    sealed trait FunctionParam

    object FixedUtility extends FunctionParam
    object Cost extends FunctionParam
    object Time extends FunctionParam

    val utilityFunctions: Map[Mode, Map[FunctionParam, UtilityFunctionOperation]] = Map(
      Car  -> Map(FixedUtility -> UtilityFunctionOperation.Intercept(3.0)),
      Walk -> Map(FixedUtility -> UtilityFunctionOperation.Intercept(4.0))
    )

    val common = Map(
      Cost -> UtilityFunctionOperation.Multiplier(-0.01),
      Time -> UtilityFunctionOperation.Multiplier(-0.02)
    )

    val mnl = new MultinomialLogit(utilityFunctions, common)

    val alts: Map[Mode, Map[FunctionParam, Double]] = Map(
      Car  -> Map(Cost -> 30.0, Time -> 50.0),
      Walk -> Map(Cost -> 0.0, Time  -> 40.0)
    )

    "should evaluate utility functions as expected" in {
      mnl.getUtilityOfAlternative(alts.head._1, alts.head._2) match {
        case None => fail()
        case Some(util) =>
          (util - 1.7).abs should be < 0.000000001
      }

    }
    "should evaluate expected max utility as expected" in {
      val util = mnl.getExpectedMaximumUtility(alts)
      Math.abs(util.get - 3.401413) < 0.00001 should be(true)
    }

    "should sample higher probability alternatives more often" in {
      // With these inputs, we expect "walk" ~81% of the time, which translates to an almost certainty that majority
      // will be walk with 100 trials (p-val 3.00491e-12)

      val sampleSize = 100
      val rand = new Random()

      val samples: Seq[Mode] = for {
        _      <- 1 until sampleSize
        result <- mnl.sampleAlternative(alts, rand)
      } yield result.alternativeType

      samples.count { _ == Walk } > (sampleSize / 2) // 50% or more should be walk

    }
  }
}
