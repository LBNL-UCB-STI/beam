package beam.utils.analysis

import java.io.Closeable
import java.util

import beam.sim.common.GeoUtils
import beam.utils.GeoJsonReader
import beam.utils.csv.{CsvWriter, GenericCsvReader}
import beam.utils.geospatial.PolygonUtil
import beam.utils.scenario.PlanElement
import beam.utils.scenario.generic.readers.CsvPlanElementReader
import beam.utils.shape.Attributes
import com.vividsolutions.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Envelope
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.geotools.MGC
import org.opengis.feature.Feature
import org.opengis.feature.simple.SimpleFeature

object NewYorkBeamVsLodes {
  private[analysis] case class GeoAttribute(
    state: String,
    county: String,
    tractCode: String,
    blockGroup: String,
    censusBlockGroup: String
  ) extends Attributes

  private[analysis] def mapper(feature: Feature): (GeoAttribute, MultiPolygon) = {
    val geom = feature.asInstanceOf[SimpleFeature].getDefaultGeometry.asInstanceOf[MultiPolygon]
    val state = feature.getProperty("StateFIPS").getValue.toString
    val county = feature.getProperty("CountyFIPS").getValue.toString
    val tractCode = feature.getProperty("TractCode").getValue.toString
    val blockGroup = feature.getProperty("BlockGroup").getValue.toString
    val censusBlockGroup = feature.getProperty("CensusBlockGroup").getValue.toString

    require(geom.isSimple)
    require(geom.isValid)

    (GeoAttribute(state, county, tractCode, blockGroup, censusBlockGroup), geom)
  }

  val geoUtils: GeoUtils = new GeoUtils {
    override def localCRS: String = "epsg:32118"
  }

  def main(args: Array[String]): Unit = {
    val pathToGeoJson = "test/input/external-data/cbg.geojson"
    val pathToPlans = "test/input/newyork/generic_scenario/1049k-NYC-related/plans.csv.gz"
    val pathToLodes = "test/input/external-data/NYC_LODES_and_distance.csv"

    val beamHomeToWorkCounts = getBeamHomeWorkCounts(pathToGeoJson, pathToPlans)
    println(s"beamHomeToWorkCounts: ${beamHomeToWorkCounts.size}")
    println(
      s"beamHomeToWorkCounts: ${beamHomeToWorkCounts.size}. Found ratio: ${beamHomeToWorkCounts.size.toDouble / beamHomeToWorkCounts.size}"
    )

    val beamHomeWorkToCountMap: Map[(String, String), Int] = beamHomeToWorkCounts
      .map { case (o, d, cnt) => (o.censusBlockGroup, d.censusBlockGroup, cnt) }
      .groupBy { case (o, d, _) => (o, d) }
      .map { case (x, xs) => (x -> xs.map(_._3).sum) }

    val lodesHomeToWorkCount = getLodesHomeWorkCounts(pathToLodes)
    println(s"lodesHomeToWorkCount: ${lodesHomeToWorkCount.size}")

    val lodesHomeWorkToCountMap: Map[(String, String), Int] = lodesHomeToWorkCount
      .groupBy { case (o, d, _) => (o, d) }
      .map { case (x, xs) => (x -> xs.map(_._3).sum) }

    val beamKeys = beamHomeWorkToCountMap.keySet
    val lodesKeys = lodesHomeWorkToCountMap.keySet
    val allKeys = beamKeys ++ lodesKeys
    println(s"beamKeys: ${beamKeys.size}")
    println(s"lodesKeys: ${lodesKeys.size}")
    println(s"beamKeys intersects with lodesKeys: ${beamKeys.intersect(lodesKeys).size}")
    println

    val csvWriter = new CsvWriter("beam_vs_lodes.csv", Array("source", "destination", "beam_count", "lodes_count"))
    allKeys.foreach { key =>
      val (src, dst) = key
      val beamCount = beamHomeWorkToCountMap.getOrElse(key, 0)
      val lodesCount = lodesHomeWorkToCountMap.getOrElse(key, 0)
      csvWriter.write("\"" + src + "\"", "\"" + dst + "\"", beamCount, lodesCount)
    }
    csvWriter.close()
  }

  private def getLodesHomeWorkCounts(pathToLodes: String): Seq[(String, String, Int)] = {
    def mapper(rec: util.Map[String, String]): (String, String, Int) = {
      val originBlockGroupId = rec.get("O_bg").toString
      val destBlockGroupId = rec.get("D_bg").toString
      val count = rec.get("S000").toInt
      (originBlockGroupId, destBlockGroupId, count)
    }

    val (iter, toClose: Closeable) =
      GenericCsvReader.readAs[(String, String, Int)](pathToLodes, mapper, _ => true)
    try {
      iter.toIndexedSeq
    } finally {
      toClose.close()
    }
  }

  private def getBeamHomeWorkCounts(
    pathToGeoJson: String,
    pathToPlans: String
  ): Seq[(GeoAttribute, GeoAttribute, Int)] = {
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
      pathToPlans,
      isUtmCoord = false
    )

    val beamBlockToBlock = homeToWork.flatMap {
      case ((origin, destination), cnt) =>
        for {
          (originAttrib, _) <- PolygonUtil.findPolygonViaQuadTree(quadTree, origin, maxDistance = 2, delta = 0.01)
          (destAttrib, _)   <- PolygonUtil.findPolygonViaQuadTree(quadTree, destination, maxDistance = 2, delta = 0.01)
        } yield (originAttrib, destAttrib, cnt)
    }
    println(s"homeToWork: ${homeToWork.size}")
    println(
      s"beamBlockToBlock: ${beamBlockToBlock.size}. Found ratio: ${beamBlockToBlock.size.toDouble / homeToWork.size}"
    )
    beamBlockToBlock
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
}
