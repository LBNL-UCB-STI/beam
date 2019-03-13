package beam.utils
import scala.util.Random

import org.apache.commons.math3.distribution.UniformRealDistribution

class Sampler(seed: Long) {

  private val realDistribution: UniformRealDistribution = new UniformRealDistribution()
  realDistribution.reseedRandomGenerator(seed)

  def uniformSample[A](numberOfElements: Int, objectsWithProbabilities: Iterable[(A, Double)]): Seq[A] = {
    val totalProbabilities = objectsWithProbabilities.map(_._2).sum
    val objectsWithRelativeProbabilities = objectsWithProbabilities.map { x =>
      (x._1, (x._2 / totalProbabilities * 100).toInt)
    }
    val distributedElements: Seq[(A, Int)] = Random.shuffle {
      for {
        element <- objectsWithRelativeProbabilities
        _       <- 1 to element._2
      } yield element
    }.toIndexedSeq

    (1 to numberOfElements).map { _ =>
      distributedElements(Random.nextInt(100))._1
    }
  }

}
