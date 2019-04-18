package beam.agentsim.agents.choice.logit

import beam.agentsim.agents.choice.logit.{Alternative, MultinomialLogit_old, UtilityFunction, UtilityFunctionParam, UtilityFunctionParamType}
import beam.sim.BeamHelper
import org.scalatest.{Matchers, WordSpecLike}

import scala.util.Random

// todo test with objects / types && token --> arbitrary

/**
  * BEAM
  */
class MultinomialLogitSpec extends WordSpecLike with Matchers with BeamHelper {

  "An MNL Model with standard data" must {
    val utilityFunctions = Vector(
      UtilityFunction[String, String](
        "car",
        Set(UtilityFunctionParam("intercept", UtilityFunctionParamType("intercept"), 3.0))
      ),
      UtilityFunction[String, String](
        "walk",
        Set(UtilityFunctionParam("intercept", UtilityFunctionParamType("intercept"), 4.0))
      )
    )

    val common = Some(
      UtilityFunction[String, String](
        "COMMON",
        Set(
          UtilityFunctionParam("cost", UtilityFunctionParamType("multiplier"), -0.01),
          UtilityFunctionParam("time", UtilityFunctionParamType("multiplier"), -0.02)
        )
      )
    )

    val mnl = MultinomialLogit(
      utilityFunctions,
      common
    )
    val rand = new Random()
    val alts = Vector(
      Alternative("car", Map("cost"  -> 30.0, "time" -> 50.0)),
      Alternative("walk", Map("cost" -> 0.0, "time"  -> 40.0))
    )

    "should evaluate utility functions as expected" in {
      val util = mnl.getUtilityOfAlternative(alts(0))
      ((util - 1.7).abs < 0.000000001) should be(true)
    }
    "should evaluate expected max utility as expected" in {
      val util = mnl.getExpectedMaximumUtility(alts)
      Math.abs(util - 3.401413) < 0.00001 should be(true)
    }
    "should sample higher probability alternatives more often" in {
      // With these inputs, we expect "walk" ~81% of the time, which translates to an almost certainty that majority
      // will be walk with 100 trials (p-val 3.00491e-12)

      val samps = for (i <- 1 until 100) yield mnl.sampleAlternative(alts, rand).get

      samps.count(_.alternativeId.equals("walk")) > 50 should be(true)
    }
  }

  "An MNL Model with arbitrary data" must {

    // the alternatives as objects
    sealed trait Mode

    object Car extends Mode
    object Walk extends Mode

    object Common extends Mode

    // the function parameters as objects
    sealed trait FunctionParam

    object Cost extends FunctionParam
    object Time extends FunctionParam

    case object Intercept extends FunctionParam

    val utilityFunctions = Vector(
      UtilityFunction[Mode, FunctionParam](
        Car,
        Set(UtilityFunctionParam(Intercept, UtilityFunctionParamType("intercept"), 3.0))
      ),
      UtilityFunction[Mode, FunctionParam](
        Walk,
        Set(UtilityFunctionParam(Intercept, UtilityFunctionParamType("intercept"), 4.0))
      )
    )

    val common = Some(
      UtilityFunction[Mode, FunctionParam](
        Common,
        Set(
          UtilityFunctionParam(Cost, UtilityFunctionParamType("multiplier"), -0.01),
          UtilityFunctionParam(Time, UtilityFunctionParamType("multiplier"), -0.02)
        )
      )
    )

    val mnl = MultinomialLogit(
      utilityFunctions,
      common
    )
    val rand = new Random()
    val alts = Vector(
      Alternative[FunctionParam, Mode](Car, Map(Cost  -> 30.0, Time -> 50.0)),
      Alternative[FunctionParam, Mode](Walk, Map(Cost -> 0.0, Time  -> 40.0))
    )

    "should evaluate utility functions as expected" in {
      val util = mnl.getUtilityOfAlternative(alts(0))
      ((util - 1.7).abs < 0.000000001) should be(true)
    }
    "should evaluate expected max utility as expected" in {
      val util = mnl.getExpectedMaximumUtility(alts)
      Math.abs(util - 3.401413) < 0.00001 should be(true)
    }
    "should sample higher probability alternatives more often" in {
      // With these inputs, we expect "walk" ~81% of the time, which translates to an almost certainty that majority
      // will be walk with 100 trials (p-val 3.00491e-12)

      val samps = for (i <- 1 until 100) yield mnl.sampleAlternative(alts, rand).get

      samps.count(_.alternativeId.equals(Walk)) > 50 should be(true)
    }
  }

}
