package beam.utils.data.synthpop.generators

import beam.router.Modes.BeamMode
import beam.utils.csv.CsvWriter
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
    BenchmarkGenerator.toBeamModes(insideBoundingBox)
  }

}

object BenchmarkGenerator {

  def toBeamModes(odList: Iterable[OD[MeansOfTransportation]]): Map[BeamMode, Double] = {
    odList
      .map { od =>
        (od.attribute.toBeamMode, od.value)
      }
      .collect { case (maybeBeamMode, value) if maybeBeamMode.nonEmpty => (maybeBeamMode.get, value) }
      .groupBy { case (beamMode, _) => beamMode }
      .map { case (mode, xs) => mode -> xs.view.map(_._2).sum }
  }

  def apply(dbInfo: CTPPDatabaseInfo, pathToTazShapeFiles: List[String], pathToOsmMap: String): BenchmarkGenerator = {
    val od =
      new MeansOfTransportationTableReader(dbInfo, ResidenceToWorkplaceFlowGeography.`TAZ To TAZ`)
        .read()
    val tazGeoIdToGeom: Map[TazGeoId, Geometry] = pathToTazShapeFiles.flatMap { pathToTazShapeFile =>
      GeoService.getTazMap("EPSG:4326", pathToTazShapeFile, x => true, GeoService.defaultTazMapper)
    }.toMap
    val mapBoundingBox: Envelope = GeoService.getBoundingBoxOfOsmMap(pathToOsmMap)
    new BenchmarkGenerator(od, tazGeoIdToGeom, mapBoundingBox)
  }

  private val pathToCTPP: String = "d:/Work/beam/CTPP/"
  private val newYorkCountiesShapeFile: String =
    "D:/Work/beam/NewYork/input/Shape/TAZ/tl_2011_36_taz10/tl_2011_36_taz10.shp"
  private val newJerseyCountiesShapeFile: String =
    "D:/Work/beam/NewYork/input/Shape/TAZ/tl_2011_34_taz10/tl_2011_34_taz10.shp"
  private val pathToOSMFile: String = "D:/Work/beam/NewYork/input/OSM/newyork-14-counties-incomplete.osm.pbf"

  def main(args: Array[String]): Unit = {
    writeModesFromNewYorkCityCounties()
    writeModesFromOsmBoundingBox()
  }

  private def writeModesFromOsmBoundingBox(): Unit = {
    val databaseInfo = CTPPDatabaseInfo(PathToData(pathToCTPP), Set("36", "34"))
    val pathToTazShapeFile = List(newYorkCountiesShapeFile, newJerseyCountiesShapeFile)
    val bg = BenchmarkGenerator.apply(databaseInfo, pathToTazShapeFile, pathToOSMFile)
    val modeToODs = bg.calculate.toSeq.sortBy { case (mode, _) => mode.toString }

    writeBeamModes("benchmark_from_osm_bounding_box.csv", modeToODs)
  }

  private def writeModesFromNewYorkCityCounties(): Unit = {
    val countiesOfNewYorkCity = List(
      ("36047", "Kings County"),
      ("36005", "Bronx County"),
      ("36061", "New York County"),
      ("36081", "Queens County"),
      ("36085", "Richmond County")
    )
    val setOfCounts = countiesOfNewYorkCity.map(_._1).toSet
    val odList =
      new MeansOfTransportationTableReader(
        CTPPDatabaseInfo(PathToData(pathToCTPP), Set("36")),
        ResidenceToWorkplaceFlowGeography.`TAZ To TAZ`
      ).read()

    println(s"Total number of OD: ${odList.size}")
    val filtered = odList.filter { od =>
      val origin = TazGeoId.fromString(od.source)
      val dest = TazGeoId.fromString(od.destination)
      val oCode = s"${origin.state.value}${origin.county.value}"
      val dCode = s"${dest.state.value}${dest.county.value}"
      setOfCounts.contains(oCode) || setOfCounts.contains(dCode)
    }
    println(s"Filtered for New York City counties: ${filtered.size}")

    val modes = BenchmarkGenerator.toBeamModes(filtered).toSeq.sortBy { case (mode, _) => mode.toString }
    writeBeamModes("benchmark_ny_counties.csv", modes)
  }

  private def writeBeamModes(path: String, modes: Seq[(BeamMode, Double)]): Unit = {
    val totalCount = modes.map(_._2).sum
    val csvWriter = new CsvWriter(path, Array("mode", "count", "percent"))
    try {
      modes.foreach {
        case (mode, count) =>
          val pct = 100 * count / totalCount
          csvWriter.write(mode, count, pct)
      }
    } finally {
      csvWriter.close()
    }
  }
}
