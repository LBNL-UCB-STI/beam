package beam.utils

import scala.annotation.tailrec

/**
  * BEAM
  */
object CollectionUtils {

  def onContains[T](xs: Seq[_], actionMappings: (_, T)*): Option[Any] = {
    actionMappings collectFirst {
      case (key, v) if xs contains key => v
    }
  }

  object Option{
    def map2[A,B,C](a: Option[A], b: Option[B])(f: (A, B) => C): Option[C] =
      a flatMap (aa => b map (bb => f(aa, bb)))
  }

  implicit class AddMultispanToList[A](val list: List[A]) extends AnyVal {
    def multiSpan(splitOn: (A) => Boolean): List[List[A]] = {
      @tailrec
      def loop(xs: List[A], acc: List[List[A]]) : List[List[A]] = xs match {
        case Nil => acc

        case x :: Nil => List(x) :: acc

        case h :: t =>
          val (pre,post) = t.span(!splitOn(_))
          loop(post, (h :: pre) :: acc)
      }
      loop(list, Nil).reverse
    }
  }
}
