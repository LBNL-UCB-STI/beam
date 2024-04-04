package beam.router.skim

import enumeratum.{Enum, EnumEntry}

import scala.collection.immutable

/**
  * @author Dmitry Openkov
  */
sealed abstract class ActivitySimMetric extends EnumEntry

object ActivitySimMetric extends Enum[ActivitySimMetric] {
  val values: immutable.IndexedSeq[ActivitySimMetric] = findValues

  case object TOTIVT extends ActivitySimMetric
  case object FERRYIVT extends ActivitySimMetric
  case object FAR extends ActivitySimMetric
  case object XWAIT extends ActivitySimMetric
  case object KEYIVT extends ActivitySimMetric
  case object DTIM extends ActivitySimMetric
  case object IWAIT extends ActivitySimMetric
  case object BOARDS extends ActivitySimMetric
  case object TNCLEGS extends ActivitySimMetric
  case object DDIST extends ActivitySimMetric
  case object WAUX extends ActivitySimMetric
  case object BTOLL extends ActivitySimMetric
  case object VTOLL extends ActivitySimMetric
  case object TIME extends ActivitySimMetric
  case object DIST extends ActivitySimMetric
  case object WEGR extends ActivitySimMetric
  case object WACC extends ActivitySimMetric
  case object IVT extends ActivitySimMetric
  case object TRIPS extends ActivitySimMetric
  case object FAILURES extends ActivitySimMetric
}
