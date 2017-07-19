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

}
