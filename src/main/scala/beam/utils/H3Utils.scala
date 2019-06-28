package beam.utils

import beam.agentsim.infrastructure.taz.H3TAZTreeMap
import beam.agentsim.infrastructure.taz.TAZTreeMap
import com.uber.h3core.util.GeoCoord
import com.vividsolutions.jts.geom.{Coordinate, GeometryFactory, Polygon}
import org.matsim.api.core.v01.Coord
import org.matsim.core.config.ConfigUtils
import org.matsim.core.population.io.PopulationReader
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation

object H3Utils {
  def main(args: Array[String]): Unit = {
    //    implicit val H3 = com.uber.h3core.H3Core.newInstance
    //    test

    import scala.collection.JavaConverters._
    val (tazMap, toWGS84) = sfBay
    val inTransform: GeotoolsTransformation = new GeotoolsTransformation("epsg:26910", "EPSG:4326")
    val poli = tazMap.getTAZs.map { taz =>
      val gf = new GeometryFactory()
      val boundary = taz.geom.map(inTransform.transform).map(c => new Coordinate(c.getX, c.getY))
      (taz.tazId.toString, gf.createPolygon(boundary :+ boundary.head))
    }
    writeHexToShp(poli, "out/test/polygons0.shp")



    val h3map = H3TAZTreeMap.fromTAZs(tazMap, "epsg:26910")
    writeHexToShp(H3TAZTreeMap.toPolygons(h3map), "out/test/polygons.shp")

    //    val sc = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    //    new PopulationReader(sc).readFile("test/input/sf-light/sample/25k/population.xml.gz")
    //    val h3map2 = H3TAZTreeMap.fromPopulation(sc.getPopulation,"epsg:26910")
    //    writeHexToShp(H3TAZTreeMap.toPolygons(h3map2), "out/test/polygons1.shp")
    println("End")
    //testTazMap(tazMap, "epsg:26910", "out/test/polygons.shp")
  }

  def hexToCoord(hexAddress: String)(implicit H3: com.uber.h3core.H3Core): Coord = {
    val coordinate = toJtsCoordinate(H3.h3ToGeo(hexAddress))
    new Coord(coordinate.x, coordinate.y)
  }

  def hexToJtsCoordinate(hexAddress: String)(implicit H3: com.uber.h3core.H3Core): Coordinate = {
    toJtsCoordinate(H3.h3ToGeo(hexAddress))
  }

  def hexToJtsPolygon(coord: java.util.List[GeoCoord]): com.vividsolutions.jts.geom.Polygon = {
    import scala.collection.JavaConverters._
    new com.vividsolutions.jts.geom.GeometryFactory()
      .createPolygon(coord.asScala.map(toJtsCoordinate).toArray :+ toJtsCoordinate(coord.get(0)))
  }

  def toJtsCoordinate(in: GeoCoord): com.vividsolutions.jts.geom.Coordinate = {
    new com.vividsolutions.jts.geom.Coordinate(in.lng, in.lat)
  }

  def toGeoCoord(in: Coord): GeoCoord = {
    new GeoCoord(in.getY, in.getX)
  }



  def geoToH3index(geo: Array[Coord], resolution: Int = 0)(implicit H3: com.uber.h3core.H3Core): List[String] = {
    import com.uber.h3core.util.GeoCoord

    import scala.collection.JavaConverters._
    val points = geo.map(c => new GeoCoord(c.getY, c.getX)).toList.asJava
    val holes = List.empty[java.util.List[GeoCoord]].asJava
    H3.polyfillAddress(points, holes, resolution).asScala.toList
  }

  def writeHexToShp(hex: List[String], filename: String)(implicit H3: com.uber.h3core.H3Core): Unit = {
    import scala.collection.JavaConverters._
    val gf = new GeometryFactory()
    val hexagons = hex.map {
      h =>
        val boundary = H3.h3ToGeoBoundary(h).asScala
        (h, gf.createPolygon(boundary.map(H3Utils.toJtsCoordinate).toArray :+ H3Utils.toJtsCoordinate(boundary.head)))
    }
    writeHexToShp(hexagons, filename)
  }

  def writeHexToShp(polygons: Iterable[(String, Polygon)], filename: String): Unit = {
    import org.matsim.core.utils.geometry.geotools.MGC
    import org.matsim.core.utils.gis.{PolygonFeatureFactory, ShapeFileWriter}
    import scala.collection.JavaConverters._
    val pf: PolygonFeatureFactory = new PolygonFeatureFactory.Builder()
      .setCrs(MGC.getCRS("EPSG:4326"))
      .setName("nodes")
      .addAttribute("ID", classOf[String])
      .create()
    val shpPolygons = polygons.map { case (hex, hexagon) =>
      pf.createPolygon(hexagon.getCoordinates, Array[Object](hex), null)
    }
    ShapeFileWriter.writeGeometries(shpPolygons.asJavaCollection, filename)
  }


  def sfBay = {
    val toWGS84: GeotoolsTransformation = new GeotoolsTransformation("epsg:26910", "EPSG:4326")
    val tazMap = TAZTreeMap.fromShapeFile("test/input/sf-light/shape/sf-light-tazs.shp", "taz")
    (tazMap, toWGS84)
  }

  def testTazMap(tazMap: TAZTreeMap, scenarioEPSG: String, filename: String) = {
    implicit val H3 = com.uber.h3core.H3Core.newInstance
    val inTransform: GeotoolsTransformation = new GeotoolsTransformation(scenarioEPSG, "EPSG:4326")
    import scala.collection.JavaConverters._
    tazMap.tazQuadTree.values().asScala.take(1).head.geom.map(inTransform.transform).foreach(println)
    val h3index =
      tazMap.tazQuadTree.values().asScala.flatMap(taz => geoToH3index(taz.geom.map(inTransform.transform), 9))
    writeHexToShp(h3index.toList, filename)
  }

  def test(implicit H3: com.uber.h3core.H3Core) = {
    import scala.collection.JavaConverters._
    val lat = 37.775938728915946
    val lng = -122.41795063018799
    val res = 9
    val hexAddr = H3.geoToH3Address(lat, lng, res)
    val hexAddrs = H3.h3ToChildren(hexAddr, res+1).asScala.toList :+ hexAddr :+ H3.h3ToParentAddress(hexAddr, res-1)
    val geoCoords = H3.h3ToGeoBoundary(hexAddr)
    val out = hexToJtsPolygon(geoCoords)
    println(out)
    writeHexToShp(hexAddrs, "output/test/polygons.shp")
  }

  def test2(implicit H3: com.uber.h3core.H3Core) = {
    val geography = Array(
      new Coord(-122.4089866999972145, 37.813318999983238),
      new Coord(-122.3805436999997056, 37.7866302000007224),
      new Coord(-122.3544736999993603, 37.7198061999978478),
      new Coord(-122.5123436999983966, 37.7076131999975672),
      new Coord(-122.5247187000021967, 37.7835871999971715),
      new Coord(-122.4798767000009008, 37.8151571999998453)
    )
    val h3index = geoToH3index(geography, 8)
    println(h3index)
    writeHexToShp(h3index, "output/test/polygons.shp")
  }

  def test3(implicit H3: com.uber.h3core.H3Core) = {
    val geography = Array(
      new Coord(-122.49118500000002, 37.646534999085276),
      new Coord(-122.49168500000003, 37.6448349990853),
      new Coord(-122.48997500000002, 37.637748999085346),
      new Coord(-122.48958500000003, 37.63303499908539),
      new Coord(-122.49358500000002, 37.62983499908543),
      new Coord(-122.49529425555099, 37.62975360596395),
      new Coord(-122.49408500000004, 37.644034999085314),
      new Coord(-122.49118500000002, 37.646534999085276)
    )
    val h3index = geoToH3index(geography, 9)
    println(h3index)
    writeHexToShp(h3index, "output/test/polygons.shp")
  }

}
