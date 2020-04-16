package beam.agentsim.infrastructure.geozone.taz

import java.nio.file.Path

import beam.agentsim.infrastructure.geozone.taz.TazToGeoIndexConverter.{GeoIndexParkingEntry, GeoIndexParkingEntryGroup}
import beam.utils.csv.CsvWriter

class GeoGrouper(
  private val parkingGroupEntries: Map[GeoIndexParkingEntryGroup, Seq[ParkingEntryValues]]
) {

  def groupValues(geoIndexParkingEntryGroup: GeoIndexParkingEntryGroup): Seq[ParkingEntryValues] = {
    parkingGroupEntries(geoIndexParkingEntryGroup)
  }

  def this(parkingEntries: Seq[GeoIndexParkingEntry]) = {
    this(GeoGrouper.generateMap(parkingEntries))
  }

  def aggregate(entryAggregator: ValueAggregator): GeoGrouper = {
    val newMap = parkingGroupEntries.map {
      case (group, values) => group -> entryAggregator.aggregate(values)
    }
    new GeoGrouper(newMap)
  }

  def exportToCsv(file: Path): GeoGrouper = {
    val csvWriter: CsvWriter = {
      val headers =
        Array("geoIndex", "parkingType", "pricingModel", "chargingType", "reservedFor", "numStalls", "feeInCents")
      new CsvWriter(file.toString, headers)
    }
    val rows = parkingGroupEntries.flatMap {
      case (group, valueses) =>
        valueses.map { entryValue: ParkingEntryValues =>
          IndexedSeq(
            group.geoIndex.value,
            group.parkingType,
            group.pricingModel,
            group.chargingType,
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
    parkingEntries: Seq[GeoIndexParkingEntry]
  ): Map[GeoIndexParkingEntryGroup, Seq[ParkingEntryValues]] = {
    val allPairs = parkingEntries.map { entry: GeoIndexParkingEntry =>
      entry.group -> ParkingEntryValues(entry.numStalls, entry.feeInCents)
    }
    allPairs
      .groupBy(_._1)
      .mapValues { x: Seq[(GeoIndexParkingEntryGroup, ParkingEntryValues)] =>
        x.map(_._2)
      }
  }
}
