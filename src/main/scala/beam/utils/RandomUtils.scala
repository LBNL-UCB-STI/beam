package beam.utils
import java.util.Random

object RandomUtils {

  def shuffle[T](it: Iterable[T], rnd: Random): Iterable[T] = {
    new scala.util.Random(rnd).shuffle(it)
  }

  def multispan[T](elems: Seq[T], p: T => Boolean): Seq[Seq[T]] =
    elems match {
      case Seq() => Seq()
      case Seq(head, tail @ _*) if !p(head) => Seq(head) +: multispan(tail, p)
      case xs =>
        val (prefix, rest) = xs span p
        prefix +: multispan(rest, p)
    }
}
