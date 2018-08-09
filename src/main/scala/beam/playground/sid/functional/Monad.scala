package beam.playground.sid.functional

import scala.language.higherKinds

/**
  * Created by sfeygin on 4/6/17.
  */
trait Monad[F[_]] extends Functor[F] {
  def unit[A](a: => A): F[A]

  def flatMap[A, B](ma: F[A])(f: A => F[B]): F[B] =
    join(map(ma)(f))

  def map[A, B](ma: F[A])(f: A => B): F[B] =
    flatMap(ma)(a => unit(f(a)))

  def map2[A, B, C](ma: F[A], mb: F[B])(f: (A, B) => C): F[C] = {
    println(s"+++ monad $ma $mb")
    flatMap(ma)(a => map(mb)(b => f(a, b)))
  }

  def sequence[A](lma: List[F[A]]): F[List[A]] =
    lma.foldRight(unit(List[A]())) { (ma, mla) =>
      println(s"from monad $ma"); map2(ma, mla)(_ :: _)
    }

  def traverse[A, B](la: List[A])(f: A => F[B]): F[List[B]] =
    la.foldRight(unit(List[B]()))((a, mlb) => map2(f(a), mlb)(_ :: _))

  def join[A](mma: F[F[A]]): F[A] = flatMap(mma)(ma => ma)
}

object Monad {

  val optionMonad: Monad[Option] {
    def flatMap[A, B](ma: Option[A])(f: A => Option[B]): Option[B]

    def unit[A](a: => A): Some[A]
  } = new Monad[Option] {
    def unit[A](a: => A) = Some(a)
    override def flatMap[A, B](ma: Option[A])(f: A => Option[B]): Option[B] = ma flatMap f
  }

  val listMonad: Monad[List] {
    def flatMap[A, B](ma: List[A])(f: A => List[B]): List[B]

    def unit[A](a: => A): List[A]
  } = new Monad[List] {
    def unit[A](a: => A) = List(a)
    override def flatMap[A, B](ma: List[A])(f: A => List[B]): List[B] = ma flatMap f
  }
}
