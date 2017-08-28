package beam.utils

/**
  * BEAM
  */
object CollectionUtils {

  def onContains[T](xs: Seq[_], actionMappings: (_, T)*): Option[T] = {
    actionMappings collectFirst {
      case (key, v) if xs contains key => v
    }
  }

  object Option{
    def map2[A,B,C](a: Option[A], b: Option[B])(f: (A, B) => C): Option[C] =
      a flatMap (aa => b map (bb => f(aa, bb)))
  }

}
