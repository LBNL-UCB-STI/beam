package beam.side.route.model.tree

import enumeratum._

import scala.collection.immutable

sealed abstract class KDimension(val name: String) extends EnumEntry

sealed trait XYCoordinate
sealed trait XYZCoordinate extends XYCoordinate

object KDimension extends Enum[KDimension] {

  case object X extends KDimension("X") with XYZCoordinate
  case object Y extends KDimension("Y") with XYZCoordinate
  case object Z extends KDimension("Z") with XYZCoordinate

  val values: immutable.IndexedSeq[KDimension] = findValues
}

sealed abstract class Dimensional(val dim: List[KDimension], val name: String) extends EnumEntry {
  type COORD
}

object Dimensional extends Enum[Dimensional] {
  import KDimension._

  case object XYZ extends Dimensional(List(X, Y, Z), "XYZ") {
    type XYZ = XYZ.type
    override type COORD = XYZCoordinate
  }
  case object XY extends Dimensional(List(X, Y), "XY") {
    type XY = XY.type
    override type COORD = XYCoordinate
  }

  val values: immutable.IndexedSeq[Dimensional] = findValues
}
