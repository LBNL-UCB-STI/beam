package beam.replanning

import enumeratum._

import scala.collection.immutable

sealed trait BeamReplanningStrategy extends EnumEntry

object BeamReplanningStrategy extends Enum[BeamReplanningStrategy] {
  val values: immutable.IndexedSeq[BeamReplanningStrategy] = findValues

  case object UtilityBasedModeChoice extends BeamReplanningStrategy
}
