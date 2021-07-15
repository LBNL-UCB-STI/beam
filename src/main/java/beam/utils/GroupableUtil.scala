package beam.utils

import scala.collection.generic.CanBuildFrom
import scala.collection.{mutable, GenTraversableOnce}
import scala.language.higherKinds

object GroupableUtil {

  implicit class ToGroupable[A, Coll[X] <: GenTraversableOnce[X]](coll: Coll[A]) {

    def groupMap[K, B, To](key: A => K)(f: A => B)(implicit bf: CanBuildFrom[Coll[A], B, To]): Map[K, To] = {
      val m = mutable.Map.empty[K, mutable.Builder[B, To]]
      for (elem <- coll) {
        val k = key(elem)
        val bldr = m.getOrElseUpdate(k, bf())
        bldr += f(elem)
      }
      var result = Map.empty[K, To]
      for ((k, v) <- m) {
        result = result + ((k, v.result()))
      }
      result
    }
  }
}
