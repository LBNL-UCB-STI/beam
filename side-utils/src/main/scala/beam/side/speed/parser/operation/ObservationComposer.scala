package beam.side.speed.parser.operation

trait ObservationComposer[M[_], T, R] {
  def compose(input: T): M[R]
}

object ObservationComposer {

  def apply[A, B, F[_]](
      implicit Composer: ObservationComposer[F, A, B]
  ): ObservationComposer[F, A, B] = Composer
}
