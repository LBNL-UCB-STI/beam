package beam.utils.analysis

import beam.sim.common.GeoUtils
import beam.utils.{GeoJsonReader, ProfilingUtils}
import beam.utils.csv.CsvWriter
import beam.utils.data.ctpp.models.ResidenceToWorkplaceFlowGeography
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData}
import beam.utils.data.ctpp.readers.flow.HouseholdIncomeTableReader
import beam.utils.map.{GeoAttribute, ShapefileReader}
import beam.utils.scenario.PlanElement
import beam.utils.scenario.generic.readers.CsvPlanElementReader
import beam.utils.shape.{Attributes, NoAttributeShapeWriter, ShapeWriter}
import com.vividsolutions.jts.geom.prep.PreparedGeometryFactory
import com.vividsolutions.jts.geom.{Geometry, LineString, MultiPolygon, Point}
import org.geotools.geometry.jts.JTS
import org.locationtech.jts.geom.Envelope
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.geotools.MGC
import org.opengis.feature.Feature
import org.opengis.feature.simple.SimpleFeature
import org.opengis.referencing.operation.MathTransform

import scala.annotation.tailrec
import scala.collection.JavaConverters._

object NewYorkBeamVsLodes {
  private case class GeoAttribute(
    state: String,
    county: String,
    tractCode: String,
    blockGroup: String,
    censusBlockGroup: String
  ) extends Attributes

  private def mapper(feature: Feature): (GeoAttribute, MultiPolygon) = {
    val geom = feature.asInstanceOf[SimpleFeature].getDefaultGeometry.asInstanceOf[MultiPolygon]
    val state = feature.getProperty("StateFIPS").getValue.toString
    val county = feature.getProperty("CountyFIPS").getValue.toString
    val tractCode = feature.getProperty("TractCode").getValue.toString
    val blockGroup = feature.getProperty("BlockGroup").getValue.toString
    val censusBlockGroup = feature.getProperty("CensusBlockGroup").getValue.toString
    (GeoAttribute(state, county, tractCode, blockGroup, censusBlockGroup), geom)
  }

  val geoUtils: GeoUtils = new GeoUtils {
    override def localCRS: String = "epsg:32118"
  }

  def main(args: Array[String]): Unit = {
    val pathToGeoJson = "C:/Users/User/Downloads/cbg.geojson"
    // Replace `urn:ogc:def:crs:OGC:1.3:CRS84` in that origina file by `EPSG:4326`
    val allFeatures = GeoJsonReader.read(pathToGeoJson, mapper)

    val envelope = allFeatures.foldLeft(new Envelope()) {
      case (env, (k, v)) =>
        val center = v.getCentroid
        env.expandToInclude(center.getX, center.getY)
        env
    }

    val quadTree =
      new QuadTree[(GeoAttribute, MultiPolygon)](envelope.getMinX, envelope.getMinY, envelope.getMaxX, envelope.getMaxY)
    allFeatures.foreach {
      case (k, v) =>
        quadTree.put(v.getCentroid.getX, v.getCentroid.getY, ((k, v)))
    }
    val homeToWork = NewYorkHomeWorkLocationAnalysis.getHomeWorkLocations(
      "test/input/newyork/generic_scenario/1049k-NYC-related/plans.csv.gz",
      isUtmCoord = false
    )
    @tailrec
    def findPolygonViaQuadTree(coord: Coord, distance: Double = 0.0): Option[(GeoAttribute, MultiPolygon)] = {
      val xs = if (distance == 0.0) {
        Seq(quadTree.getClosest(coord.getX, coord.getY))
      } else {
        quadTree.getDisk(coord.getX, coord.getY, distance).asScala
      }
      xs.find { case (_, polygon) => polygon.contains(MGC.coord2Point(coord)) } match {
        case Some(value: (GeoAttribute, MultiPolygon)) =>
          Some(value)
        case None if distance <= 2 =>
          None
        case None =>
          findPolygonViaQuadTree(coord, distance + 0.01)
      }
    }

    var originIsNotInside: Int = 0
    var destIsNotInside: Int = 0

    val shapeWriter = NoAttributeShapeWriter.worldGeodetic[Point](s"not_inside_of_polygon.shp")

    var idx: Int = 0

    homeToWork.foreach {
      case ((origin, destination), cnt) =>
        val x = findPolygonViaQuadTree(origin)
        val y = findPolygonViaQuadTree(destination)

        if (x.isEmpty) {
          shapeWriter.add(MGC.coord2Point(origin), idx.toString)
          idx += 1
          originIsNotInside += 1
        }
        if (y.isEmpty) {
          shapeWriter.add(MGC.coord2Point(destination), idx.toString)
          idx += 1
          destIsNotInside += 1
        }

//      val isFoundOriginInside = originPolygon.contains(MGC.coord2Point(origin))
//      if (!isFoundOriginInside) originIsNotInside += 1
//
//      val isFoundDestInside = destPolygon.contains(MGC.coord2Point(destination))
//      if (!isFoundDestInside) destIsNotInside += 1

//      println(s"originAttrib: $originAttrib, isFoundOriginInside: $isFoundOriginInside")
//      println(s"destAttrib: $destAttrib, isFoundDestInside: $isFoundDestInside")
    }
    shapeWriter.write()
    println(s"homeToWork: ${homeToWork.size}")
    println(s"originIsNotInside: $originIsNotInside, ratio: ${originIsNotInside.toDouble / homeToWork.size}")
    println(s"destIsNotInside: $destIsNotInside, ratio: ${destIsNotInside.toDouble / homeToWork.size}")

  }

  private def computeBeamScenario(
    boroughMap: Map[String, MultiPolygon],
    pathToPlans: String,
    outputCsvPath: String,
    isUtmCoord: Boolean
  ): Unit = {
    println("###### computeBeamScenario started ####")
    println("######################################################################")
    println(s"Plans: $pathToPlans")

    val homeWorkLocationsFromScenario = getHomeWorkLocations(pathToPlans, isUtmCoord)
    println(s"homeWorkLocationsFromScenario: ${homeWorkLocationsFromScenario.size}")

    val envelope = boroughMap.foldLeft(new Envelope()) {
      case (env, (k, v)) =>
        val center = v.getCentroid
        env.expandToInclude(center.getX, center.getY)
        env
    }

    val quadTree =
      new QuadTree[(String, MultiPolygon)](envelope.getMinX, envelope.getMinY, envelope.getMaxX, envelope.getMaxY)
    boroughMap.foreach {
      case (k, v) =>
        quadTree.put(v.getCentroid.getX, v.getCentroid.getY, (k, v))
    }

    def getViaQuadTree(coord: Coord): Option[String] = {
      val (borough, polygon) = quadTree.getClosest(coord.getX, coord.getY)
      if (polygon.contains(MGC.coord2Point(coord))) Some(borough)
      else None
    }

    val homeWorkBoroughToBorough = homeWorkLocationsFromScenario.par.flatMap {
      case ((src, dst), count) =>
        val maybeSrcGeoId = getViaQuadTree(src).fold {
          boroughMap
            .find {
              case (_, polygon) =>
                polygon.contains(MGC.coord2Point(src))
            }
            .map(_._1)
        }(Some(_))
        val maybeDstGeoId = getViaQuadTree(dst).fold {
          boroughMap
            .find {
              case (_, polygon) =>
                polygon.contains(MGC.coord2Point(dst))
            }
            .map(_._1)
        }(Some(_))

        for {
          srcGeoId <- maybeSrcGeoId
          dstGeoId <- maybeDstGeoId
        } yield ((srcGeoId, dstGeoId), count)
    }.seq
    println(s"homeWorkBoroughToBorough: ${homeWorkBoroughToBorough.size}")

    val boroughToBoroughAggregated = homeWorkBoroughToBorough
      .groupBy { case ((src, dst), _) => (src, dst) }
      .map { case ((src, dst), xs) => ((src, dst), xs.map(_._2).sum) }
    println(s"boroughToBoroughAggregated: ${boroughToBoroughAggregated.size}")

    val csvWriter = new CsvWriter(outputCsvPath, Array("source", "destination", "count"))

    boroughToBoroughAggregated.foreach {
      case ((src, dst), count) =>
        csvWriter.write(src, dst, count)
    }
    csvWriter.close()

    println("######################################################################")
    println("###### computeBeamScenario finshed ####")

  }

  private def getHomeWorkLocations(pathToPlans: String, isUtmCoord: Boolean): Seq[((Coord, Coord), Double)] = {
    def filter(planElement: PlanElement): Boolean = {
      planElement.planSelected && planElement.planElementType == "activity" && planElement.activityType.exists(
        actType => actType.toLowerCase == "home" || actType.toLowerCase == "work"
      )
    }

    val (tempPlans, toClose) = CsvPlanElementReader.readWithFilter(pathToPlans, filter)
    try {
      tempPlans.toArray
        .groupBy { x =>
          x.personId
        }
        .toSeq
        .flatMap {
          case (_, xs) =>
            if (xs.length < 2) None
            else {
              val sorted = xs.sortBy(x => x.activityEndTime.getOrElse(Double.MaxValue))
              val homeWorkActivities = sorted
                .sliding(2, 1)
                .filter { xs =>
                  xs.length == 2
                }
                .filter { xs =>
                  val isFirstOk = xs(0).activityType.exists(actType => actType.toLowerCase == "home")
                  val isSecondOk = xs(1).activityType.exists(actType => actType.toLowerCase == "work")
                  isFirstOk && isSecondOk
                }
                .toVector
              homeWorkActivities.map { sorted =>
                val srcCoord = new Coord(sorted(0).activityLocationX.get, sorted(0).activityLocationY.get)
                val srcWgsCoord = if (isUtmCoord) geoUtils.utm2Wgs(srcCoord) else srcCoord
                val dstCoord = new Coord(sorted(1).activityLocationX.get, sorted(1).activityLocationY.get)
                val dstWgsCoord = if (isUtmCoord) geoUtils.utm2Wgs(dstCoord) else dstCoord
                Some(((srcWgsCoord, dstWgsCoord), 1))
              }
            }
        }
        .flatten
        .groupBy { case ((src, dst), _) => (src, dst) }
        .map { case (x, xs) => (x, xs.map(_._2).sum.toDouble) }
        .toSeq
    } finally {
      toClose.close()
    }
  }

  def readTaz(path: String): Map[String, MultiPolygon] = {
    val crsCode: String = "EPSG:4326"
    def map(mathTransform: MathTransform, feature: SimpleFeature): (String, MultiPolygon) = {
      val fullTractGeoId = feature.getAttribute("GEOID10").toString
      val geom = PreparedGeometryFactory.prepare(feature.getDefaultGeometry.asInstanceOf[Geometry])
      val wgsGeom = JTS.transform(geom.getGeometry, mathTransform).asInstanceOf[MultiPolygon]
      (fullTractGeoId, wgsGeom)
    }
    ShapefileReader.read(crsCode, path, _ => true, map).toMap
  }

  def readBorough(path: String): Map[String, MultiPolygon] = {
    val crsCode: String = "EPSG:4326"
    def map(mathTransform: MathTransform, feature: SimpleFeature): (String, MultiPolygon) = {
      val boroughName = feature.getAttribute("boro_name").toString
      val geom = PreparedGeometryFactory.prepare(feature.getDefaultGeometry.asInstanceOf[Geometry])
      val wgsGeom = JTS.transform(geom.getGeometry, mathTransform).asInstanceOf[MultiPolygon]
      (boroughName, wgsGeom)
    }
    ShapefileReader.read(crsCode, path, _ => true, map).toMap
  }
}
