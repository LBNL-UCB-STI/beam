package beam.utils.data.synthpop.generators

import beam.router.Modes.BeamMode
import beam.utils.data.ctpp.models.{MeansOfTransportation, OD, ResidenceToWorkplaceFlowGeography}
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData}
import beam.utils.data.ctpp.readers.flow.MeansOfTransportationTableReader
import beam.utils.data.synthpop.GeoService
import beam.utils.data.synthpop.models.Models.TazGeoId
import com.vividsolutions.jts.geom.{Envelope, Geometry}
import de.vandermeer.asciitable.AsciiTable

class BenchmarkGenerator(
  val odList: Iterable[OD[MeansOfTransportation]],
  val tazGeoIdToGeom: Map[TazGeoId, Geometry],
  val mapBoundingBox: Envelope
) {

  def calculate: Map[BeamMode, Double] = {
    val insideBoundingBox = odList.filter { od =>
      val origin = TazGeoId.fromString(od.source)
      val dest = TazGeoId.fromString(od.destination)
      val originGeo = tazGeoIdToGeom.get(origin)
      val destGeo = tazGeoIdToGeom.get(dest)

      val isInside = for {
        originCenter <- originGeo.map(_.getCentroid)
        destCenter   <- destGeo.map(_.getCentroid)
        isOriginInside = mapBoundingBox.contains(originCenter.getX, originCenter.getY)
        isDestInside = mapBoundingBox.contains(destCenter.getX, destCenter.getY)
      } yield isOriginInside && isDestInside
      isInside.getOrElse(false)
    }
    insideBoundingBox
      .map { od =>
        (od.attribute.toBeamMode, od.value)
      }
      .collect { case (maybeBeamMode, value) if maybeBeamMode.nonEmpty => (maybeBeamMode.get, value) }
      .groupBy { case (beamMode, _) => beamMode }
      .map { case (mode, xs) => mode -> xs.view.map(_._2).sum }
  }


}

object BenchmarkGenerator {

  def apply(dbInfo: CTPPDatabaseInfo, pathToTazShapeFile: String, pathToOsmMap: String): BenchmarkGenerator = {
    val od =
      new MeansOfTransportationTableReader(dbInfo, ResidenceToWorkplaceFlowGeography.`TAZ To TAZ`)
        .read()
    val tazGeoIdToGeom: Map[TazGeoId, Geometry] =
      GeoService.getTazMap("EPSG:4326", pathToTazShapeFile, x => true, GeoService.defaultTazMapper).toMap
    val mapBoundingBox: Envelope = GeoService.getBoundingBoxOfOsmMap(pathToOsmMap)
    new BenchmarkGenerator(od, tazGeoIdToGeom, mapBoundingBox)
  }

  def main(args: Array[String]): Unit = {
    val databaseInfo = CTPPDatabaseInfo(PathToData("d:/Work/beam/Austin/input/CTPP/"), Set("48"))
    val pathToTazShapeFile = """D:\Work\beam\Austin\input\tl_2011_48_taz10\tl_2011_48_taz10.shp"""
    val pathToOSMFile = """D:\Work\beam\Austin\input\texas-six-counties-simplified.osm.pbf"""
    val bg = BenchmarkGenerator.apply(databaseInfo, pathToTazShapeFile, pathToOSMFile)
    val modeToODs = bg.calculate.toSeq.sortBy { case (mode, _) => mode.toString }
    val total = modeToODs.map(_._2).sum

    val table = new AsciiTable()
    table.addRule()
    table.addRow("mode", "count", "percent")

    modeToODs.foreach {
      case (mode, count) =>
        val pct = 100 * count / total
        table.addRule()
        table.addRow(mode.toString, count.toString, pct.toString)
    }
    table.addRule()
    println(table.render())
  }
}
