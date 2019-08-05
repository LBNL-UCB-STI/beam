package beam.side.speed.parser.operation
import beam.side.speed.model.{FilterEventAction, UberWaySpeed}

trait ObservationFilter[M[_], T <: FilterEventAction] {
  def filter(speed: UberWaySpeed): M[T#FilterEvent]
}

object ObservationFilter {
  def apply[M[_], T <: FilterEventAction](implicit Filter: ObservationFilter[M, T]): ObservationFilter[M, T] = Filter
}
