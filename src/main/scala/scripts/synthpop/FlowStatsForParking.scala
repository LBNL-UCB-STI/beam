package scripts.synthpop

import scripts.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData}
import beam.utils.data.synthpop.GeoService.{defaultTazMapper, getTazMap}
import beam.utils.data.synthpop.models.Models.TazGeoId
import com.typesafe.scalalogging.StrictLogging
import org.locationtech.jts.geom.Geometry
import org.opengis.feature.simple.SimpleFeature
import org.opengis.referencing.operation.MathTransform
import scripts.ctpp.models.{OD, ResidenceGeography, ResidenceToWorkplaceFlowGeography}
import scripts.ctpp.readers.flow.TravelTimeTableReader
import scripts.ctpp.readers.residence.TotalPopulationTableReader

class FlowStatsForParking(val dbInfo: CTPPDatabaseInfo, val tazGeoIdToGeomAndLandArea: Map[TazGeoId, (Geometry, Long)])
    extends StrictLogging {

  case class Row(
    sourceTaz: String,
    destinationTaz: String,
    numberOfWorkers: Int,
    totalPopulation: Int,
    sourceArea: Long,
    destinationArea: Long
  )

  private val travelTimeOD: Iterable[OD[Range]] =
    new TravelTimeTableReader(dbInfo, ResidenceToWorkplaceFlowGeography.`TAZ To TAZ`).read()

  private val odToNumberOfWorkers: Map[(String, String), Double] = travelTimeOD
    .map { x =>
      val tupledKey = (x.source, x.destination)
      (tupledKey, x.value)
    }
    .groupBy { case (key, _) => key }
    .map { case (key, xs) =>
      key -> xs.map(_._2).sum
    }

  private val totalPopulation = new TotalPopulationTableReader(dbInfo, ResidenceGeography.TAZ).read()

  private val allRows: Iterable[Row] = odToNumberOfWorkers.map { case ((src, dst), numberOfWorkers) =>
    val srcData = tazGeoIdToGeomAndLandArea.get(TazGeoId.fromString(src))
    val dstData = tazGeoIdToGeomAndLandArea.get(TazGeoId.fromString(dst))
    val totalPop = totalPopulation.getOrElse(src, 0)
    Row(src, dst, numberOfWorkers.toInt, totalPop, srcData.map(_._2).getOrElse(0), dstData.map(_._2).getOrElse(0))
  }
  logger.info(s"allRows: ${allRows.size}")
}

object FlowStatsForParking {

  def main(args: Array[String]): Unit = {
    val pathToTazShapeFile = """D:\Work\beam\Austin\input\tl_2011_48_taz10\tl_2011_48_taz10.shp"""
    val databaseInfo = CTPPDatabaseInfo(PathToData("d:/Work/beam/Austin/input/CTPP/"), Set("48"))

    def mapper(mathTransform: MathTransform, feature: SimpleFeature) = {
      val (tazGeoId, geom, _) = defaultTazMapper(mathTransform, feature)
      val landArea = feature.getAttribute("ALAND10").asInstanceOf[Long]
      (tazGeoId, (geom, landArea))
    }

    val tazGeoIdToGeomAndLandArea: Map[TazGeoId, (Geometry, Long)] =
      getTazMap("EPSG:4326", pathToTazShapeFile, _ => true, mapper).toMap
    val flowStatsForParking = new FlowStatsForParking(databaseInfo, tazGeoIdToGeomAndLandArea)

    val rows = flowStatsForParking.allRows
    println(s"flowStatsForParking rows size: ${rows.size}")

    println("Sample first 10 rows:")
    println(s"${rows.take(10).mkString("\n")}")
  }
}
