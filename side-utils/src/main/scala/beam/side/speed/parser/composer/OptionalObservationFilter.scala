package beam.side.speed.parser.composer
import beam.side.speed.model.{FilterEventAction, UberWaySpeed, WayFilter}
import beam.side.speed.parser.operation.ObservationFilter

import scala.reflect.ClassTag

class OptionalObservationFilter[T <: FilterEventAction](fOpt: T#Filtered)(
    implicit t: ClassTag[T],
    wf: WayFilter[T#FilterEvent, T#Filtered]
) extends ObservationFilter[Option, T] {
  override def filter(speed: UberWaySpeed): Option[T#FilterEvent] =
    Some(speed.waySpeed[T](fOpt))
}
