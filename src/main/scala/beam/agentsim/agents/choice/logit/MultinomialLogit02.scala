package beam.agentsim.agents.choice.logit
import scala.collection.immutable.SortedSet
import scala.util.Random

import com.typesafe.scalalogging.LazyLogging

/**
  * a generic Multinomial Logit Function for modeling utility functions over discrete alternatives
  * @param utilityFunctions mappings from alternatives to the attributes which can be evaluated against them
  * @param common common attributes of all alternatives
  * @tparam A the type of alternatives we are choosing between
  * @tparam T the attributes of this multinomial logit function
  */
class MultinomialLogit02[A, T](
  utilityFunctions: Map[A, Map[T, MNLOperation]],
  common: Map[T, MNLOperation]
) extends LazyLogging {


  /**
    * Sample over a set of types A by calculating the probabilities of each alternative
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
    alternatives: Map[A, Map[T, Double]],
    random: Random
  ): Option[MultinomialLogit02.MNLSample[A]] = {
    if (alternatives.isEmpty) None
    else {

      // one-pass evaluation and sorting
      val altsWithUtilitySortedDesc: Iterable[(A, Double)] =
        alternatives.foldLeft(SortedSet.empty[(A, Double)](Ordering.by{-_._2})) { case (set, (alt, attributes)) =>
          getUtilityOfAlternative(alt, attributes) match {
            case None => set
            case Some(thisUtility) =>
              set + ((alt, math.exp(thisUtility)))
          }
      }

      altsWithUtilitySortedDesc.headOption.flatMap { case (possiblyInfiniteAlt, possiblyInfinite) =>
        if (possiblyInfinite == Double.PositiveInfinity) {
          // take the first infinitely-valued alternative
          Some { MultinomialLogit02.MNLSample(possiblyInfiniteAlt, possiblyInfinite, 1.0, 1.0) }
        } else {

          // denominator used for transforming utility values into draw probabilities
          val sumOfExponentialUtilities: Double = altsWithUtilitySortedDesc.map{ case (_, u) => u }.sum

          // transform alternatives into a list in descending order by successive draw thresholds (prefix-stacked probabilities)
          val asProbabilitySpread: List[MultinomialLogit02.MNLSample[A]] =
            altsWithUtilitySortedDesc.
              foldLeft((0.0, List.empty[MultinomialLogit02.MNLSample[A]])){ case ((prefix, stackedProbabilitiesList), (alt, expUtility)) =>
                val probability: Double = expUtility / sumOfExponentialUtilities
                val nextDrawThreshold: Double = prefix + probability
                val nextStackedProbabilitiesList = MultinomialLogit02.MNLSample(alt, expUtility, nextDrawThreshold, probability) +: stackedProbabilitiesList

                (nextDrawThreshold, nextStackedProbabilitiesList)
              }._2

          val randomDraw: Double = random.nextDouble

          // take the first alternative which exceeds the random draw
          // we discard while the probability's draw threshold exceeds the random draw
          // and will leave us with a list who's first element is the largest just below the draw value
          asProbabilitySpread.
            dropWhile{ _.drawThreshold > randomDraw }.
            headOption
        }
      }
    }
  }


  /**
    * Get the expected maximum utility over a set of types A
    *
    * @param alternatives the alternatives that should be evaluated
    * @return
    */
  def getExpectedMaximumUtility(
    alternatives: Map[A, Map[T, Double]]
  ): Option[Double] = {
    val utilityOfAlternatives: Iterable[Double] =
      for {
        (alt, attributes) <- alternatives
        utility <- getUtilityOfAlternative(alt, attributes)
      } yield {
        utility
      }

    if (utilityOfAlternatives.isEmpty) None
    else Some { Math.log(utilityOfAlternatives.sum) }
  }


  /**
    * Calculate the utility of the provided alternative based on the utility functions provided during the initialization of
    * the MultinomialLogit model. If the provided utility functions are not able to evaluate the provided alternative
    * (e.g. there is no function for the provided alternative) the provided utility is -1E100
    *
    * @param alternative the alternative to evaluate
    * @param attributes a set of utility function attributes and their corresponding values for this alternative
    * @return some utility value, or, None if the MNL does not know this alternative or there are no matching attributes
    */
  def getUtilityOfAlternative(
    alternative: A,
    attributes: Map[T, Double]
  ): Option[Double] = {

    // get common utility values even if they aren't present in the alternative
    val commonUtility: Iterable[Double] = for {
      (attrs, mnlOperation) <- common
      functionParam = attributes.getOrElse(attrs, 0.0)
    } yield {
      mnlOperation(functionParam)
    }

    val alternativeUtility: Iterable[Double] = for {
      utilFnsForAlt <- utilityFunctions.get(alternative).toList
      attribute       <- utilFnsForAlt.keys.toSet.union(attributes.keys.toSet).toList
      mnlOperation  <- utilFnsForAlt.get(attribute)
      functionParam <- attributes.get(attribute)
    } yield {
      mnlOperation(functionParam)
    }

    commonUtility ++ alternativeUtility match {
      case Nil => None
      case totalUtility: Iterable[Double] => Some{ totalUtility.sum }
    }
  }
}

object MultinomialLogit02 {

  case class MNLSample[AlternativeType](alternativeType: AlternativeType, utility: Double, drawThreshold: Double, realProbability: Double)

  val ZeroValue: Double = -1E100
}

sealed trait MNLOperation {
  def apply(value: Double): Double
}

object MNLOperation {
  case class Intercept(coefficient: Double) extends MNLOperation {
    override def apply(value: Double): Double = coefficient
  }
  case class Multiplier(coefficient: Double) extends MNLOperation {
    override def apply(value: Double): Double = coefficient * value
  }
}


// thoughts
//   "should sample higher probability alternatives more often" spec is dangerous
//   i'm not using ZeroValue, just Option.. is ok?
//   breaks first test in spec