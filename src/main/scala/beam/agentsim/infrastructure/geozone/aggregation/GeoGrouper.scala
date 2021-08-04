package beam.agentsim.infrastructure.geozone.aggregation

import java.nio.file.Path

import beam.agentsim.infrastructure.geozone.aggregation.ParkingH3IndexConverter.{
  H3IndexParkingEntry,
  H3IndexParkingEntryGroup
}
import beam.utils.csv.CsvWriter

class GeoGrouper(
  val parkingGroupEntries: Map[H3IndexParkingEntryGroup, Seq[ParkingEntryValues]]
) {

  def groupValues(geoIndexParkingEntryGroup: H3IndexParkingEntryGroup): Seq[ParkingEntryValues] = {
    parkingGroupEntries.getOrElse(geoIndexParkingEntryGroup, Seq.empty)
  }

  def this(parkingEntries: Seq[H3IndexParkingEntry]) = {
    this(GeoGrouper.generateMap(parkingEntries))
  }

  def aggregate(entryAggregator: ValueAggregator): GeoGrouper = {
    val newMap = parkingGroupEntries.map { case (group, values) =>
      group -> entryAggregator.aggregate(values)
    }
    new GeoGrouper(newMap)
  }

  def exportToCsv(file: Path): GeoGrouper = {
    val csvWriter: CsvWriter = {
      val headers =
        Array("geoIndex", "parkingType", "pricingModel", "chargingPointType", "reservedFor", "numStalls", "feeInCents")
      new CsvWriter(file.toString, headers)
    }
    val rows = parkingGroupEntries.flatMap { case (group, values) =>
      values.map { entryValue: ParkingEntryValues =>
        IndexedSeq(
          group.h3Index.value,
          group.parkingType,
          group.pricingModel,
          group.chargingPointType,
          group.reservedFor,
          entryValue.numStalls,
          entryValue.feeInCents
        )
      }
    }
    rows.foreach(csvWriter.writeRow)
    csvWriter.flush()
    this
  }

}

object GeoGrouper {

  private def generateMap(
    parkingEntries: Seq[H3IndexParkingEntry]
  ): Map[H3IndexParkingEntryGroup, Seq[ParkingEntryValues]] = {
    val allPairs = parkingEntries.map { entry: H3IndexParkingEntry =>
      entry.group -> ParkingEntryValues(entry.numStalls, entry.feeInCents)
    }
    allPairs
      .groupBy(_._1)
      .mapValues { x: Seq[(H3IndexParkingEntryGroup, ParkingEntryValues)] =>
        x.map(_._2)
      }
  }
}
