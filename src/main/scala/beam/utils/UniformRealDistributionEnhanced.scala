package beam.utils

import org.apache.commons.math3.distribution.UniformRealDistribution

class UniformRealDistributionEnhanced extends UniformRealDistribution {
  val rnd: scala.util.Random = new scala.util.Random()

  override def reseedRandomGenerator(seed: Long): Unit = {
    rnd.setSeed(seed)
    super.reseedRandomGenerator(seed)
  }

  def shuffle[T](listOfObjectsToSample: List[T]): List[T] = rnd.shuffle(listOfObjectsToSample)

  def nextInt(length: Int): Int = rnd.nextInt(length)
}
