package beam.utils.hereapi

import java.nio.file.{Path, Paths}

import scala.language.implicitConversions

import beam.agentsim.infrastructure.geozone.{GeoZoneUtil, WgsCoordinate}
import beam.utils.csv.CsvWriter
import beam.utils.hereapi.HereExampleUsage.result

class RichSegments(segments: Seq[HereSegment]) {

  def saveToCsv(path: Path): Seq[HereSegment] = {
    val csvWriter: CsvWriter = {
      val headers = Array("wgsCoordinates", "lengthInMeters", "speedLimitInKph")
      new CsvWriter(path.toString, headers)
    }
    val rows = segments.map { segment =>
      IndexedSeq(
        toCsv(segment.coordinates),
        segment.lengthInMeters,
        segment.speedLimitInKph.getOrElse("")
      )
    }
    rows.foreach(csvWriter.writeRow)
    csvWriter.flush()
    segments
  }

  def saveToShapeFile(outputFile: Path): Seq[HereSegment] = {
    val allCoordinates = segments.flatMap(_.coordinates).toSet
    val outputFile: Path = Paths.get("outputShapeFile.shx")
    GeoZoneUtil.writeToShapeFile(outputFile, allCoordinates, resolution = 12)
    segments
  }

  private def toCsv(wgsCoordinates: Seq[WgsCoordinate]): String = {
    wgsCoordinates
      .map { coord =>
        s"${coord.latitude}/${coord.longitude}"
      }
      .mkString("|")
  }

}

object RichSegments {
  implicit def toRich(segments: Seq[HereSegment]): RichSegments = new RichSegments(segments)
}
