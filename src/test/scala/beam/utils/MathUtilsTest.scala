package beam.utils

import org.apache.commons.math3.stat.inference.ChiSquareTest
import org.scalatest.FunSuite
import org.scalatest.Matchers

class MathUtilsTest extends FunSuite with Matchers {

  test("selectElementsByProbability edge cases") {
    val xs = (0 to 10).toArray
    def alwaysSelectProbabilityFunction(n: Int): Double = 1
    MathUtils.selectElementsByProbability(42, alwaysSelectProbabilityFunction, xs) shouldBe xs

    def neverSelectProbabilityFunction(n: Int): Double = 0
    MathUtils.selectElementsByProbability(42, neverSelectProbabilityFunction, xs) shouldBe Array.empty
  }

  test("selectElementsByProbability all probabilities are equal") {
    // Chi-Square test for simple case when the probability of selecting element is the same for all elements

    val xs = (0 to 10).toArray
    def probabilityFunction(n: Int): Double = 1.toDouble / xs.length

    val nTests: Int = 1000
    val expectedFreq = xs.map { _ =>
      nTests * probabilityFunction(1)
    }
    val selectedLength = (1 to nTests).foldLeft(Array.fill[Long](xs.length)(0)) {
      case (acc, _) =>
        val selected = MathUtils.selectElementsByProbability(System.nanoTime(), probabilityFunction, xs)
        // Fill in frequency table
        selected.foreach { idx =>
          acc(idx) += 1
        }
        acc
    }
    val ct = new ChiSquareTest()
    // confidence level 99%
    val alpha: Double = 0.01
    val pValue = ct.chiSquareTest(expectedFreq, selectedLength)
    val isRejected = ct.chiSquareTest(expectedFreq, selectedLength, alpha)
    require(!isRejected, s"Null hypothesis is rejected. Confidence interval was $alpha, P-value: $pValue")
  }

  test("selectElementsByProbability with different probabilities") {
    // Chi-Square test for simple case when the probability of selecting element not equal

    val xs = (0 to 100).toArray
    def probabilityFunction(n: Int): Double = {
      if (n >= 0 & n < 30) 0.1
      else if (n >= 30 & n < 45) 0.3
      else if (n >= 45 & n < 55) 0.6
      else if (n >= 55 & n < 70) 0.75
      else if (n >= 70 & n < 80) 0.84
      else if (n >= 80 & n < 95) 0.89
      else if (n == 95) 0.95
      else if (n == 96) 0.96
      else if (n == 97) 0.97
      else if (n == 98) 0.98
      else if (n == 99) 0.99
      else if (n == 100) 1.0
      else throw new IllegalStateException(s"${n}")
    }

    val nTests: Int = 1000
    val expectedFreq = xs.map { n =>
      nTests * probabilityFunction(n)
    }
    val selectedLength = (1 to nTests).foldLeft(Array.fill[Long](xs.length)(0)) {
      case (acc, _) =>
        val selected = MathUtils.selectElementsByProbability(System.nanoTime(), probabilityFunction, xs)
        // Fill in frequency table
        selected.foreach { idx =>
          acc(idx) += 1
        }
        acc
    }
    val ct = new ChiSquareTest()
    // confidence level 99%
    val alpha: Double = 0.01
    val pValue = ct.chiSquareTest(expectedFreq, selectedLength)
    val isRejected = ct.chiSquareTest(expectedFreq, selectedLength, alpha)
    require(!isRejected, s"Null hypothesis is rejected. Confidence interval was $alpha, P-value: $pValue")
  }
}
