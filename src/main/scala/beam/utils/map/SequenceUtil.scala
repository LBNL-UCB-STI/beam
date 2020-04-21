package beam.utils.map

import scala.collection.parallel.ParSeq

object SequenceUtil {

  def groupBy[K, V](pairs: Seq[(K, V)]): Map[K, Set[V]] = {
    val groupedByKey = pairs.groupBy(_._1)
    groupedByKey.mapValues { pairs: Iterable[(K, V)] =>
      pairs.map(_._2).toSet
    }
  }

  def groupBy[K, V](pairs: ParSeq[(K, V)]): Map[K, Set[V]] = {
    val groupedByKey = pairs.groupBy(_._1)
    groupedByKey.mapValues { pairs: ParSeq[(K, V)] =>
      pairs.map(_._2).toSet.seq
    }.seq.toMap
  }

}
