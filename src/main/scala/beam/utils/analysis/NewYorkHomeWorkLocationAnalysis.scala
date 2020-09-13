package beam.utils.analysis

import beam.utils.ProfilingUtils
import beam.utils.csv.CsvWriter
import beam.utils.data.ctpp.models.ResidenceToWorkplaceFlowGeography
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData}
import beam.utils.data.ctpp.readers.flow.HouseholdIncomeTableReader
import beam.utils.map.ShapefileReader
import beam.utils.shape.Attributes
import com.vividsolutions.jts.geom.prep.PreparedGeometryFactory
import com.vividsolutions.jts.geom.{Geometry, MultiPolygon}
import org.geotools.geometry.jts.JTS
import org.opengis.feature.simple.SimpleFeature
import org.opengis.referencing.operation.MathTransform

private case class Attrib(nPeople: Int) extends Attributes

object NewYorkHomeWorkLocationAnalysis {

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
    val newYorkTazs = readTaz("D:/Work/beam/NewYork/input/Shape/TAZ/tl_2011_36_taz10/tl_2011_36_taz10.shp")
      .filter { case (tazGeoId, _) => allTazGeoIds.contains(tazGeoId) }
    println(s"newYorkTazs: ${allTazGeoIds.size}")

    // Borough Boundaries shape file from https://data.cityofnewyork.us/City-Government/Borough-Boundaries/tqmj-j8zm
    val boroughMap: Map[String, MultiPolygon] = readBorough(
      "D:/Work/beam/NewYork/input/Shape/Others/Borough Boundaries/geo_export_77031048-d035-4f7d-bd07-d568f2b54acb.shp"
    )
    println(s"boroughMap: ${boroughMap.size}")

    val tazGeoIdToBorough = ProfilingUtils.timed("TAZ to Borough", x => println(x)) {
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

    val boroughToBorough = srcDstToPeople
      .map {
        case ((src, dst), cnt) =>
          ((tazGeoIdToBorough.get(src), tazGeoIdToBorough.get(dst)), cnt)
      }
      .groupBy { case ((src, dst), _) => (src, dst) }
      .map { case ((src, dst), xs) => ((src, dst), xs.map(_._2).sum) }
    println(s"boroughToBorough: ${boroughToBorough.size}")

    val csvWriter = new CsvWriter("household_income_borough.csv", Array("source", "destination", "count"))

    boroughToBorough.foreach {
      case ((src, dst), count) =>
        csvWriter.write(src, dst, count)
        println(s"${src}:${dst} = $count")
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
