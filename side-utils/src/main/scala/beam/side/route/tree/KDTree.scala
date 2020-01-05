package beam.side.route.tree

import beam.side.route.model.tree.KDimension

sealed trait KDTree[T <: AnyVal, A <: KDimension => T] {}
