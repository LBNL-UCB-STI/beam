package beam.side.speed.parser.operation

trait Program[F[_]] {
  def flatMap[A, B](fa: F[A], afb: A => F[B]): F[B]
  def map[A, B](fa: F[A], ab: A => B): F[B]
}

object Program {
  implicit class ProgramSyntax[F[_], A](fa: F[A]) {
    def map[B](f: A => B)(implicit F: Program[F]): F[B] = F.map(fa, f)
    def flatMap[B](afb: A => F[B])(implicit F: Program[F]): F[B] = F.flatMap(fa, afb)
  }

  def apply[F[_]](implicit F: Program[F]): Program[F] = F
}

trait ProgramInterprets {
  implicit object ProgramOption extends Program[Option] {
    override def flatMap[A, B](fa: Option[A], afb: A => Option[B]): Option[B] = fa.flatMap(afb)

    override def map[A, B](fa: Option[A], ab: A => B): Option[B] = fa.map(ab)

  }
}
