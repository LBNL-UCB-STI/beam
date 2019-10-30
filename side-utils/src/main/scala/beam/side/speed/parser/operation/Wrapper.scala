package beam.side.speed.parser.operation

trait Wrapper[F[_]] {
  def flatMap[A, B](fa: F[A], afb: A => F[B]): F[B]
  def map[A, B](fa: F[A], ab: A => B): F[B]
  def zip[A, B >: A](fLeft: F[A], fRight: F[A]): F[B]
}

object Wrapper {
  implicit class WrapperSyntax[F[_], A](fa: F[A]) {
    def map[B](f: A => B)(implicit F: Wrapper[F]): F[B] = F.map(fa, f)
    def flatMap[B](afb: A => F[B])(implicit F: Wrapper[F]): F[B] =
      F.flatMap(fa, afb)
    def zip[B >: A](fRight: F[A])(implicit F: Wrapper[F]): F[B] =
      F.zip(fa, fRight)
  }

  def apply[F[_]](implicit F: Wrapper[F]): Wrapper[F] = F
}

trait ProgramInterprets {
  implicit object WrapperOption extends Wrapper[Option] {
    override def flatMap[A, B](fa: Option[A], afb: A => Option[B]): Option[B] =
      fa.flatMap(afb)

    override def map[A, B](fa: Option[A], ab: A => B): Option[B] = fa.map(ab)

    override def zip[A, B >: A](fLeft: Option[A],
                                fRight: Option[A]): Option[B] =
      fLeft.orElse(fRight)
  }
}
