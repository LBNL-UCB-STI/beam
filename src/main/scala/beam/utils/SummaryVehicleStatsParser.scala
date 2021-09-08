package beam.utils

import scala.collection.immutable.HashSet
import scala.collection.mutable

object SummaryVehicleStatsParser {

  def splitToStatVehicle(stat_vehicle: String): Option[(String, String)] = {
    stat_vehicle.indexOf("_") match {
      case idx if idx > 0 && idx < stat_vehicle.length - 1 =>
        val (stat, vehicle) = stat_vehicle.splitAt(idx)
        Some((stat, vehicle.tail))
      case _ => None
    }
  }

  def splitStatsMap(
    stats: Map[String, Double],
    columns: Seq[String]
  ): (Seq[String], Map[String, IndexedSeq[Double]]) = {
    val statNamesIndexes = columns.zipWithIndex.toMap
    val selectedStatNames = HashSet(columns: _*)
    def statNameIsSelected(statName: String): Boolean = selectedStatNames.contains(statName)

    case class ParsedState(statName: String, vehicleType: String, statValue: Double, statValueIdx: Int)

    case class Accumulator(
      ignoredStats: mutable.ListBuffer[String] = mutable.ListBuffer.empty[String],
      selectedStats: mutable.ListBuffer[ParsedState] = mutable.ListBuffer.empty[ParsedState]
    )

    val Accumulator(ignoredStats, selectedStats) = stats
      .foldLeft(Accumulator()) { case (accumulator, (statName_vehicleType, statValue)) =>
        splitToStatVehicle(statName_vehicleType) match {
          case Some((statName, vehicleType)) if statNameIsSelected(statName) =>
            statNamesIndexes.get(statName) match {
              case Some(idx) => accumulator.selectedStats += ParsedState(statName, vehicleType, statValue, idx)
              case _         => accumulator.ignoredStats += statName_vehicleType
            }

          case _ => accumulator.ignoredStats += statName_vehicleType
        }

        accumulator
      }

    val processed = selectedStats
      .foldLeft(mutable.Map.empty[String, mutable.ArrayBuffer[Double]]) {
        case (vehicleStatsRecords, ParsedState(_, vehicleType, statValue, statValueIdx)) =>
          vehicleStatsRecords.get(vehicleType) match {
            case None =>
              val statValues = mutable.ArrayBuffer.fill(columns.length)(0.0)
              statValues(statValueIdx) = statValue
              vehicleStatsRecords(vehicleType) = statValues

            case Some(statValues) => statValues(statValueIdx) = statValue
          }

          vehicleStatsRecords
      }
      .toMap

    (ignoredStats, processed)
  }
}
