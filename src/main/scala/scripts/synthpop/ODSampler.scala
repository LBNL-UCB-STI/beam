package scripts.synthpop

import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.random.RandomGenerator
import org.apache.commons.math3.util.{Pair => CPair}
import scripts.ctpp.models.OD

import scala.collection.JavaConverters._

object ODSampler {

  def sample[T](xs: Iterable[OD[T]], rndGen: RandomGenerator): Option[OD[T]] = {
    if (xs.isEmpty) {
      None
    } else {
      val probabilityMassFunction = xs.map { od =>
        new CPair[OD[T], java.lang.Double](od, od.value)
      }.toVector
      val distr = new EnumeratedDistribution(rndGen, probabilityMassFunction.asJava)
      val sampledTazGeoId = distr.sample()
      Some(sampledTazGeoId)
    }
  }
}
