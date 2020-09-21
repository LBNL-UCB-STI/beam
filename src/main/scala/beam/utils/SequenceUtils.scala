package beam.utils

/**
  * This class contains methods equivalent to those available in scala 2.13
  * and should be removed once scala version is upgraded
  */
object SequenceUtils {

  def minByOpt[A, B](seq: Seq[A], f: A => B)(implicit cmp: Ordering[B]): Option[A] = {
    if (seq.isEmpty)
      None
    else
      Some(seq.minBy(f)(cmp))
  }

  def maxByOpt[A, B](seq: Seq[A], f: A => B)(implicit cmp: Ordering[B]): Option[A] = {
    if (seq.isEmpty)
      None
    else
      Some(seq.maxBy(f)(cmp))
  }

  def minOption[A](seq: Iterable[A])(implicit cmp: Ordering[A]): Option[A] = {
    if (seq.isEmpty)
      None
    else
      Some(seq.min(cmp))
  }

  def maxOption[A](seq: Iterable[A])(implicit cmp: Ordering[A]): Option[A] = {
    if (seq.isEmpty)
      None
    else
      Some(seq.max(cmp))
  }

}
