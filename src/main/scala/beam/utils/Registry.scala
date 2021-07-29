package beam.utils

/**
  * Generic Registery to map names to class
  * @tparam T
  */
trait Registry[T] {

  val entries: Set[T]

  def withName(name: String): T = {
    entries
      .find(_.getClass.getSimpleName.dropRight(1) == name)
      .getOrElse(throw new IllegalArgumentException(s"There is no implementation for `$name`"))
  }
}
