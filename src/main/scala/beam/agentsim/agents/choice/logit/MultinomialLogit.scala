package beam.agentsim.agents.choice.logit

import beam.utils.MathUtils

import scala.util.Random
import com.typesafe.scalalogging.LazyLogging

/**
  * a generic Multinomial Logit Function for modeling utility functions over discrete alternatives
  *
  * @param utilityFunctions mappings from alternatives to the attributes which can be evaluated against them
  * @param common common attributes of all alternatives
  * @param scale_factor amount by which utilites are scaled before evaluating probabilities. Smaller numbers leads to less determinism
  * @tparam A the type of alternatives we are choosing between
  * @tparam T the attributes of this multinomial logit function
  */
class MultinomialLogit[A, T](
  val utilityFunctions: A => Option[Map[T, UtilityFunctionOperation]],
  common: Map[T, UtilityFunctionOperation],
  scale_factor: Double = 1.0
) extends LazyLogging {

  import MultinomialLogit._

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
  ): Option[MultinomialLogit.MNLSample[A]] = {
    if (alternatives.isEmpty) None
    else {
      sampleAlternative(calcAlternativesWithUtility(alternatives), random)
    }
  }

  def calcAlternativesWithUtility(
    alternatives: Map[A, Map[T, Double]]
  ): Iterable[AlternativeWithUtility[A]] = {
    // evaluate utility of alternatives
    val altsWithUtility: Iterable[AlternativeWithUtility[A]] =
      alternatives.foldLeft(List.empty[AlternativeWithUtility[A]]) { case (accumulator, (alt, attributes)) =>
        getUtilityOfAlternative(alt, attributes) match {
          case None => accumulator
          case Some(thisUtility: Double) =>
            if (thisUtility.isPosInfinity) {
              // place on tail of list, allowing us to short-circuit the sampling in next step
              accumulator :+ AlternativeWithUtility(
                alt,
                thisUtility * scale_factor,
                math.exp(thisUtility * scale_factor)
              )
            } else {
              AlternativeWithUtility(
                alt,
                thisUtility * scale_factor,
                math.exp(thisUtility * scale_factor)
              ) +: accumulator
            }
        }
      }

    altsWithUtility
  }

  def sampleAlternative(
    altsWithUtility: Iterable[AlternativeWithUtility[A]],
    random: Random
  ): Option[MultinomialLogit.MNLSample[A]] = {
    altsWithUtility.lastOption.flatMap {
      case AlternativeWithUtility(possiblyInfiniteAlt, possiblyInfiniteUtility, possiblyInfiniteExpUtility) =>
        if (possiblyInfiniteExpUtility.isPosInfinity) {
          // take any infinitely-valued alternative
          Some { MultinomialLogit.MNLSample(possiblyInfiniteAlt, possiblyInfiniteUtility, 1.0, 1.0) }
        } else {

          // denominator used for transforming utility values into draw probabilities
          val sumOfExponentialUtilities: Double = altsWithUtility.map { _.expUtility }.sum

          // build the cumulative distribution function (cdf) by transforming alternatives into a list
          // in ascending order of thresholds (== descending order of alternative utilities)
          // by successive draw thresholds
          val asProbabilitySpread: Vector[MultinomialLogit.MNLSample[A]] =
            altsWithUtility
              .foldLeft((0.0, Vector.empty[MultinomialLogit.MNLSample[A]])) {
                case ((prefix, stackedProbabilitiesList), AlternativeWithUtility(alt, utility, expUtility)) =>
                  val probability: Double = expUtility / sumOfExponentialUtilities
                  val nextDrawThreshold: Double = prefix + probability
                  val mnlSample = MultinomialLogit.MNLSample(
                    alt,
                    utility,
                    nextDrawThreshold,
                    probability
                  )

                  val nextStackedProbabilitiesList = stackedProbabilitiesList :+ mnlSample
                  (nextDrawThreshold, nextStackedProbabilitiesList)
              }
              ._2

          val randomDraw: Double = random.nextDouble

          // we discard while the probability's draw threshold is below or equal the random draw
          // and will leave us with a list who's first element is the largest just below or equal the draw value
          asProbabilitySpread.dropWhile { _.drawThreshold <= randomDraw }.headOption
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
    val scaledUtilityOfAlternatives: Iterable[Double] =
      for {
        (alt, attributes) <- alternatives
        utility           <- getUtilityOfAlternative(alt, attributes)
      } yield {
        utility * scale_factor
      }

    if (scaledUtilityOfAlternatives.isEmpty) None
    else Some { MathUtils.logSumExp(scaledUtilityOfAlternatives) / scale_factor }
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
      utilFnsForAlt <- utilityFunctions(alternative).toList
      //FIXME don't need this union?, see issue https://github.com/LBNL-UCB-STI/beam/issues/2862
      attribute    <- utilFnsForAlt.keys.toSet.union(attributes.keys.toSet).toList
      mnlOperation <- utilFnsForAlt.get(attribute)
      functionParam = attributes.getOrElse(attribute, 0.0)
    } yield {
      mnlOperation(functionParam)
    }

    commonUtility ++ alternativeUtility match {
      case Nil                            => None
      case totalUtility: Iterable[Double] => Some { totalUtility.sum }
    }
  }
}

object MultinomialLogit {

  case class AlternativeWithUtility[A](
    alternative: A,
    utility: Double,
    expUtility: Double
  ) {
    override def hashCode: Int = alternative.hashCode
  }

  case class MNLSample[AlternativeType](
    alternativeType: AlternativeType,
    utility: Double,
    drawThreshold: Double,
    realProbability: Double
  )

  def apply[A, T](utilityFunctions: Map[A, Map[T, UtilityFunctionOperation]]): MultinomialLogit[A, T] = {
    new MultinomialLogit(utilityFunctions.get, Map.empty)
  }

  def apply[A, T](
    utilityFunctions: Map[A, Map[T, UtilityFunctionOperation]],
    commonUtilityFunction: Map[T, UtilityFunctionOperation]
  ): MultinomialLogit[A, T] = {
    new MultinomialLogit(utilityFunctions.get, commonUtilityFunction, 1.0)
  }

  def apply[A, T](
    utilityFunctions: Map[A, Map[T, UtilityFunctionOperation]],
    commonUtilityFunction: Map[T, UtilityFunctionOperation],
    scale_factor: Double
  ): MultinomialLogit[A, T] = {
    new MultinomialLogit(utilityFunctions.get, commonUtilityFunction, scale_factor)
  }
}
