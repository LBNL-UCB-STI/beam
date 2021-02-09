package beam.utils.analysis

import beam.sim.common.GeoUtils
import beam.utils.ProfilingUtils
import beam.utils.csv.CsvWriter
import beam.utils.data.ctpp.models.ResidenceToWorkplaceFlowGeography
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData}
import beam.utils.data.ctpp.readers.flow.HouseholdIncomeTableReader
import beam.utils.map.ShapefileReader
import beam.utils.scenario.PlanElement
import beam.utils.scenario.generic.readers.CsvPlanElementReader
import beam.utils.shape.Attributes
import com.vividsolutions.jts.geom.prep.PreparedGeometryFactory
import com.vividsolutions.jts.geom.{Geometry, MultiPolygon}
import org.geotools.geometry.jts.JTS
import org.locationtech.jts.geom.Envelope
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.geotools.MGC
import org.opengis.feature.simple.SimpleFeature
import org.opengis.referencing.operation.MathTransform

private case class Attrib(nPeople: Int) extends Attributes

object NewYorkHomeWorkLocationAnalysis {

  val geoUtils: GeoUtils = new GeoUtils {
    override def localCRS: String = "epsg:32118"
  }

  def main(args: Array[String]): Unit = {
    // Source is uploaded to our S3: https://s3.us-east-2.amazonaws.com/beam-outputs/index.html#new_city/ctpp/
    val databaseInfo = CTPPDatabaseInfo(PathToData("d:/Work/beam/CTPP/"), Set("36"))
    val readData =
      new HouseholdIncomeTableReader(databaseInfo, ResidenceToWorkplaceFlowGeography.`TAZ To TAZ`)
        .read()
        .toVector
    val nonZeros = readData.filter(x => x.value != 0.0)
    val distinctHomeLocations = readData.map(_.source).distinct.size
    val distintWorkLocations = readData.map(_.destination).distinct.size
    val sumOfValues = readData.map(_.value).sum

    println(s"Read ${readData.size} OD pairs. ${nonZeros.size} is non-zero")
    println(s"distinctHomeLocations: $distinctHomeLocations")
    println(s"distintWorkLocations: $distintWorkLocations")
    println(s"sumOfValues: ${sumOfValues.toInt}")

    val srcDstToPeople = readData
      .groupBy(x => (x.source, x.destination))
      .map {
        case ((src, dst), xs) =>
          ((src, dst), xs.map(_.value).sum)
      }
      .toSeq
      .sortBy(x => -x._2)
    println(s"srcDstToPeople: ${srcDstToPeople.size}")

    val allTazGeoIds: Set[String] = srcDstToPeople.flatMap { case ((src, dst), _) => List(src, dst) }.toSet
    println(s"allTazGeoIds: ${allTazGeoIds.size}")

    // TAZ Shape file for New York state: https://catalog.data.gov/dataset/tiger-line-shapefile-2011-2010-state-new-york-2010-census-traffic-analysis-zone-taz-state-based29d50/resource/25db8b3b-86d1-4ba0-8f05-2ea371ab0d26
    val newYorkTazs: Map[String, MultiPolygon] =
      readTaz("D:/Work/beam/NewYork/input/Shape/TAZ/tl_2011_36_taz10/tl_2011_36_taz10.shp")
        .filter { case (tazGeoId, _) => allTazGeoIds.contains(tazGeoId) }
    println(s"newYorkTazs: ${allTazGeoIds.size}")

    // Borough Boundaries shape file from https://data.cityofnewyork.us/City-Government/Borough-Boundaries/tqmj-j8zm
    val boroughMap: Map[String, MultiPolygon] = readBorough(
      "D:/Work/beam/NewYork/input/Shape/Others/Borough Boundaries/geo_export_77031048-d035-4f7d-bd07-d568f2b54acb.shp"
    )
    println(s"boroughMap: ${boroughMap.size}")

    val tazGeoIdToBorough: Map[String, String] = ProfilingUtils.timed("TAZ to Borough", x => println(x)) {
      newYorkTazs.par.map {
        case (tazGeoId, tazGeom) =>
          boroughMap.find { case (_, boroughGeom) => !boroughGeom.intersection(tazGeom).isEmpty } match {
            case Some((borough, _)) => (tazGeoId, borough)
            case None =>
              (tazGeoId, "other")
          }
      }.seq
    }
    println(s"tazGeoIdToBorough: ${tazGeoIdToBorough.size}")

    computeFullStats(srcDstToPeople, tazGeoIdToBorough, "household_income_borough.csv")
    println

    ProfilingUtils.timed("Compute for 200k scenario", println) {
      computeBeamScenario(
        boroughMap,
        "https://beam-outputs.s3.amazonaws.com/output/newyork/nyc-200k-crowding-exp8__2020-09-11_18-09-53_gsq/ITERS/it.0/0.plans.csv.gz",
        "beam_200k_scenario_derived_from_location_household_income_borough.csv",
        isUtmCoord = true
      )
    }
    println

    ProfilingUtils.timed("Compute for 1049k scenario", println) {
      computeBeamScenario(
        boroughMap,
        "test/input/newyork/generic_scenario/1049k-NYC-related/plans.csv.gz",
        "beam_full_scenario_derived_from_location_household_income_borough.csv",
        isUtmCoord = false
      )
    }
    println

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

  def getHomeWorkLocations(pathToPlans: String, isUtmCoord: Boolean): Seq[((Coord, Coord), Int)] = {
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
//                val srcActivity = sorted(0).copy(activityLocationX = Some(srcWgsCoord.getX), activityLocationY =  Some(srcWgsCoord.getY))
//                val dstActivity = sorted(1).copy(activityLocationX = Some(dstWgsCoord.getX), activityLocationY =  Some(dstWgsCoord.getY))
                Some(((srcWgsCoord, dstWgsCoord), 1))
              }
            }
        }
        .flatten
        .groupBy { case ((src, dst), _) => (src, dst) }
        .map { case (x, xs) => (x, xs.map(_._2).sum) }
        .toSeq
    } finally {
      toClose.close()
    }
  }

  private def computeFullStats(
    srcDstToPeople: Seq[((String, String), Double)],
    tazGeoIdToBorough: Map[String, String],
    outputCsvPath: String
  ): Unit = {
    val boroughToBorough = srcDstToPeople
      .map {
        case ((src, dst), cnt) =>
          ((tazGeoIdToBorough.get(src), tazGeoIdToBorough.get(dst)), cnt)
      }
      .groupBy { case ((src, dst), _) => (src, dst) }
      .map { case ((src, dst), xs) => ((src, dst), xs.map(_._2).sum) }
    println(s"boroughToBorough: ${boroughToBorough.size}")

    val csvWriter = new CsvWriter(outputCsvPath, Array("source", "destination", "count"))

    boroughToBorough.foreach {
      case ((src, dst), count) =>
        csvWriter.write(src, dst, count)
    }
    csvWriter.close()
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
