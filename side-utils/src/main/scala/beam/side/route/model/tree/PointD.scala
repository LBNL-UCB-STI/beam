package beam.side.route.model.tree

trait PointD[T <: AnyVal, D <: Dimensional] extends (D#COORD => T)
