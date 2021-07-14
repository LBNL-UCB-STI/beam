package beam.agentsim.agents.choice.logit

import scala.util.Random
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class MultinomialLogitSpec extends AnyWordSpecLike with Matchers {

  "An MNL Model with standard data" must {
    val utilityFunctions = Map(
      "car"  -> Map("intercept" -> UtilityFunctionOperation.Intercept(3.0)),
      "walk" -> Map("intercept" -> UtilityFunctionOperation.Intercept(4.0))
    )

    val common = Map(
      "cost" -> UtilityFunctionOperation.Multiplier(-0.01),
      "time" -> UtilityFunctionOperation.Multiplier(-0.02)
    )

    val mnl = MultinomialLogit(utilityFunctions, common)

    val alts = Map(
      "car"  -> Map("cost" -> 30.0, "time" -> 50.0),
      "walk" -> Map("cost" -> 0.0, "time" -> 40.0)
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

    val mnl = MultinomialLogit(utilityFunctions, common)

    val alts: Map[Mode, Map[FunctionParam, Double]] = Map(
      Car  -> Map(Cost -> 30.0, Time -> 50.0),
      Walk -> Map(Cost -> 0.0, Time -> 40.0)
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
  "an MNL sampling alternatives where at least one has utility which is positively infinite" should {
    "select one of them" in new MultinomialLogitSpec.InfinitelyValuedAlternatives {
      val random: Random = new Random(0)
      val mnl: MultinomialLogit[String, String] = new MultinomialLogit[String, String](Map.empty.get, utilityFunctions)
      mnl.sampleAlternative(alternatives, random) match {
        case None           => fail()
        case Some(selected) =>
          // these alternatives have a dangerous cost value
          selected.alternativeType should (equal("B") or equal("D"))

          // the alternatives that "blow up" to infinity have a dangerous cost
          selected.utility should equal(dangerousCostValue)

          // the dangerous cost value, when e is raised to them, should go to pos. infinity
          math.pow(math.E, dangerousCostValue) should equal(Double.PositiveInfinity)
      }
    }
  }

  "an MNL with n alternatives where all have equal value" should {
    "select one with 1/n probability" in new MultinomialLogitSpec.EquallyValuedAlternatives {
      val random: Random = new Random(0)
      val mnl: MultinomialLogit[String, String] = new MultinomialLogit[String, String](_ => None, utilityFunctions)
      mnl.sampleAlternative(alternatives, random) match {
        case None           => fail()
        case Some(selected) =>
          // there are four equal alternatives, so, they should each be given a 25% probability of selection
          selected.realProbability should equal(0.25)

          // the utility should be the same
          selected.utility should equal(alternativesCost)
      }
    }
  }
}

object MultinomialLogitSpec {

  trait InfinitelyValuedAlternatives {
    val dangerousCostValue = 1000.0

    val utilityFunctions = Map(
      "value" -> UtilityFunctionOperation.Multiplier(1.0)
    )

    // alternatives B and D should evaluate to e^1000 which is greater than Double.MaxValue => infinite
    val alternatives: Map[String, Map[String, Double]] = Map(
      "A" -> Map(
        "value" -> -0.5
      ),
      "B" -> Map(
        "value" -> dangerousCostValue
      ),
      "C" -> Map(
        "value" -> 2.0
      ),
      "D" -> Map(
        "value" -> dangerousCostValue
      )
    )
  }

  trait EquallyValuedAlternatives {
    val alternativesCost: Int = -1

    val utilityFunctions = Map(
      "value" -> UtilityFunctionOperation.Multiplier(1.0)
    )

    val alternatives: Map[String, Map[String, Double]] = Map(
      "A" -> Map(
        "value" -> alternativesCost
      ),
      "B" -> Map(
        "value" -> alternativesCost
      ),
      "C" -> Map(
        "value" -> alternativesCost
      ),
      "D" -> Map(
        "value" -> alternativesCost
      )
    )
  }
}
