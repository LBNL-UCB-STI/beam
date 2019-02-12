package beam.utils
import java.util.Random

import scala.annotation.tailrec

object RandomUtils {

  def shuffle[T](it: Iterable[T], rnd: Random): Iterable[T] = {
    new scala.util.Random(rnd).shuffle(it)
  }

  def multiSpan[A](xs: List[A])(splitOn: (A) => Boolean): List[List[A]] = {
    @tailrec
    def loop(xs: List[A], acc: List[List[A]]): List[List[A]] = xs match {
      case Nil => acc

      case x :: Nil => List(x) :: acc

      case h :: t =>
        val (pre, post) = t.span(!splitOn(_))
        loop(post, (h :: pre) :: acc)
    }
    loop(xs, Nil).reverse
  }
}
