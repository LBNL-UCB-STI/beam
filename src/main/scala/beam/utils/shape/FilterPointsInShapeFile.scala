package beam.utils.shape

import beam.utils.csv.CsvWriter
import beam.utils.data.synthpop.GeoService.findShapeFile
import beam.utils.map.ShapefileReader
import beam.utils.scenario.urbansim.censusblock.entities.InputPlanElement
import beam.utils.scenario.urbansim.censusblock.reader.PlanReader
import com.vividsolutions.jts.geom.{Coordinate, Geometry, GeometryFactory}
import com.vividsolutions.jts.geom.prep.PreparedGeometryFactory
import org.geotools.geometry.jts.JTS
import org.opengis.feature.simple.SimpleFeature
import org.opengis.referencing.operation.MathTransform

object FilterPointsInShapeFile {

  def geometryReader(mathTransform: MathTransform, feature: SimpleFeature): Geometry = {
    val geom = PreparedGeometryFactory.prepare(feature.getDefaultGeometry.asInstanceOf[Geometry])
    val wgsGeom = JTS.transform(geom.getGeometry, mathTransform)
    wgsGeom
  }

  def readGeometries(pathToShapeFolder: String, crsCode: String): IndexedSeq[Geometry] = {
    val geometries: IndexedSeq[Geometry] = findShapeFile(pathToShapeFolder).flatMap { pathToShapeFile =>
      val geoms = ShapefileReader.read(crsCode, pathToShapeFile.getPath, _ => true, geometryReader)
      geoms
    }
    geometries
  }

  def readPersonToPointFromPlans(pathToPlansFile: String): IndexedSeq[(String, Geometry)] = {
    val planReader = new PlanReader(pathToPlansFile)
    val gf = new GeometryFactory()

    val points = planReader
      .iterator()
      .flatMap {
        case InputPlanElement(personId, _, _, _, _, Some(x), Some(y), _) => Some(personId, x, y)
        case _                                                           => None
      }
      .map { case (personId, x, y) => (personId, gf.createPoint(new Coordinate(x, y))) }

    points.toIndexedSeq
  }

  def main(args: Array[String]): Unit = {
    val pathToShp = "/home/nikolay/Downloads/Oakland_Alamenda_TAZ/Oakland+Alameda+TAZ/Oakland+Alameda+TAZ"
    val crs = "epsg:4326"
    val geoms = readGeometries(pathToShp, crs)
    println(s"read ${geoms.length} geoms from shapefile")

    val plansPath = "/mnt/data/work/beam/beam/production/sfbay/gemini/activitysim-plans-base-2010/plans.csv.gz"
    val personToPoint = readPersonToPointFromPlans(plansPath)
    println(s"read ${personToPoint.length} points from plans")

    val setOfPersonsWithActivitiesWithinShapeFile = personToPoint
      .filter { case (_, point) =>
        geoms.exists(geometry => geometry.contains(point))
      }
      .map { case (personId, _) => personId }
      .toSet

    println(s"found ${setOfPersonsWithActivitiesWithinShapeFile.size} persons with activities within shapefile")

    val outputPath = "/home/nikolay/Downloads/Oakland_Alamenda_TAZ/Oakland+Alameda+TAZ/persons_withing_shapefile.csv.gz"
    val csvWriter = new CsvWriter(outputPath, headers = Seq("personId"))

    setOfPersonsWithActivitiesWithinShapeFile.foreach { personId =>
      csvWriter.writeRow(IndexedSeq(personId))
    }
    csvWriter.close()

    println(s"${setOfPersonsWithActivitiesWithinShapeFile.size} persons ids written out into $outputPath")
  }
}
