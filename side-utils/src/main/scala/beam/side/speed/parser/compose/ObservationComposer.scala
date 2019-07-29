package beam.side.speed.parser.compose

trait ObservationComposer[M[_], T, R] {
  def compose(input: T): M[R]
}

trait ObservationMapper[M[_]] {
  def map()
}
