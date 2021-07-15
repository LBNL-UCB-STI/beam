package beam.utils.map

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardOpenOption}

import beam.utils.csv.CsvWriter
import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.geom.Geometry
import com.vividsolutions.jts.geom.prep.PreparedGeometryFactory
import org.geotools.geometry.jts.JTS
import org.opengis.feature.simple.SimpleFeature
import org.opengis.referencing.operation.MathTransform

import scala.util.Try

object OsmosisPolygonFilterGenerator extends StrictLogging {

  // How to run
  // ./gradlew execute -PmainClass=beam.utils.map.OsmosisPolygonFilterGenerator -PappArgs="['D:/Work/beam/Austin/input/tl_2017_us_county/tl_2017_us_county.shp', '48209,48055', 'D:/Work/beam/Austin/results']" -PmaxRAM=4g
  // 48209 - is geo id for the county. First two digits represent state code, in this case it is 48 which is Texas. Last three digits represent county code, in this case it is 209 which is Hays county
  def main(args: Array[String]): Unit = {
    require(args.length == 3)
    val pathToCountyShapeFile = args(0)
    val geoIds = if (args(1) == "") Set.empty[String] else args(1).split(",").map(_.trim.toLowerCase).toSet
    val pathToOutputFolder = args(2)

    logger.info(s"pathToCountyShapeFile: $pathToCountyShapeFile")
    logger.info(s"geoIds to take: $geoIds")
    logger.info(s"pathToOutputFolder: $pathToOutputFolder")

    val countyWithGeom: Array[(String, Geometry)] = readShape(pathToCountyShapeFile, geoIds)
    logger.info(s"countyWithGeom: ${countyWithGeom.length}")

    // You can use QGis to see how does the result geometry look
    writeWktForDebuggingPurpose(pathToOutputFolder, countyWithGeom)

    createOsmosisPolygonFilterFile(pathToOutputFolder, countyWithGeom)
  }

  private def createOsmosisPolygonFilterFile(
    pathToOutputFolder: String,
    countyToGeom: Seq[(String, Geometry)]
  ): Unit = {
    val sb = new java.lang.StringBuffer()
    def write(s: String): Unit = {
      sb.append(s)
      sb.append(System.lineSeparator())
    }
    write("counties")
    countyToGeom.foreach { case (county, geom) =>
      write(county)
      geom.getCoordinates.foreach { coord =>
        val s = s"\t${coord.getOrdinate(0)}\t${coord.getOrdinate(1)}"
        write(s)
      }
      write("END")
    }
    write("END")

    val path = pathToOutputFolder + "/counties.poly"
    Files.write(
      new File(path).toPath,
      sb.toString.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING
    )

    // Once this is done, you can crop the input PBF by using the following command:
    // `D:\Work\beam\Austin\osmosis\bin\osmosis.bat --read-pbf file="D:\Work\beam\Austin\texas-latest.osm.pbf" --log-progress --bounding-polygon file="D:\Work\beam\Austin\results\counties.poly" completeWays=yes completeRelations=yes clipIncompleteEntities=true --tf reject-ways highway=service,proposed,construction,abandoned,platform,raceway --write-pbf file="d:\Work\beam\Austin\input\texas-six-counties-simplified.osm.pbf"`
  }

  private def readShape(path: String, geoIds: Set[String]): Array[(String, Geometry)] = {
    def filter(feature: SimpleFeature): Boolean = {
      val geoId = feature.getAttribute("GEOID").toString
      geoIds.contains(geoId)
    }
    def map(mt: MathTransform, feature: SimpleFeature): (String, Geometry) = {
      val countyName = feature.getAttribute("NAME").toString.toLowerCase
      val geom = PreparedGeometryFactory.prepare(feature.getDefaultGeometry.asInstanceOf[Geometry])
      val wgsGeom = JTS.transform(geom.getGeometry, mt)
      (countyName, wgsGeom)
    }

    ShapefileReader.read("EPSG:4326", path, filter, map)
  }

  private def writeWktForDebuggingPurpose(
    pathToOutputPolygonFilterFile: String,
    countyToGeom: Seq[(String, Geometry)]
  ): Unit = {
    val resultGeomFilePath = pathToOutputPolygonFilterFile + "/wkt.csv"
    val writer = new CsvWriter(resultGeomFilePath, Array("county", "wkt"))
    try {
      countyToGeom.foreach { case (county, geom) =>
        writer.write(county, "\"" + geom.toText + "\"")
      }
    } finally {
      Try(writer.close())
    }
  }
}
