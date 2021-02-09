package beam.utils.analysis

import java.io.Closeable
import java.util

import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.common.GeoUtils
import beam.utils.GeoJsonReader
import beam.utils.analysis.NewYorkBeamVsLodes.GeoAttribute
import beam.utils.csv.{CsvWriter, GenericCsvReader}
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.mutable

object NewYorkBeamVsLodesWithinTAZ {

  val geoUtils: GeoUtils = new GeoUtils {
    override def localCRS: String = "epsg:32118"
  }

  def main(args: Array[String]): Unit = {
    val pathToGeoJson = "test/input/external-data/cbg.geojson"
    val pathToPlans = "test/input/newyork/generic_scenario/1049k-NYC-related/plans.csv.gz"
    val pathToLodes = "test/input/external-data/NYC_LODES_and_distance.csv"
    val pathToTazes = "test/input/newyork/taz-centers.csv.gz"

    val tazMap = TAZTreeMap.fromCsv(pathToTazes)
    val homeToWork: Seq[((Coord, Coord), Int)] =
      NewYorkHomeWorkLocationAnalysis
        .getHomeWorkLocations(pathToPlans, isUtmCoord = false)
        .map { case ((orig, dst), cnt) => ((geoUtils.wgs2Utm(orig), geoUtils.wgs2Utm(dst)), cnt) }
    val beamMap: Map[(Id[TAZ], Id[TAZ]), Int] = getBeamHomeWorkTAZ(tazMap, homeToWork)

    val geoData: Array[(GeoAttribute, Coord)] = GeoJsonReader
      .read(pathToGeoJson, feature => {
        val (geoAttr, polygon) = NewYorkBeamVsLodes.mapper(feature)
        (geoAttr, polygon.getCentroid)
      })
      .map { case (attribute, point) => (attribute, geoUtils.wgs2Utm(new Coord(point.getX, point.getY))) }

    val lodesHomeToWorkCount: Seq[(String, String, Int)] = getLodesHomeWorkCounts(pathToLodes)

    val lodesMap: Map[(Id[TAZ], Id[TAZ]), Int] = getLodesHomeWorkCountsMap(tazMap, geoData, lodesHomeToWorkCount)

    println(s"beamMap: ${beamMap.size}")

    val beamKeys = beamMap.keySet
    val lodesKeys = lodesMap.keySet
    val allKeys = beamKeys ++ lodesKeys
    println(s"beamKeys: ${beamKeys.size}")
    println(s"lodesKeys: ${lodesKeys.size}")
    println(s"beamKeys intersects with lodesKeys: ${beamKeys.intersect(lodesKeys).size}")
    println

    val csvWriter = new CsvWriter("beam_vs_lodes.csv.gz", Array("source", "destination", "beam_count", "lodes_count"))
    allKeys.foreach { key =>
      val (src, dst) = key
      val beamCount = beamMap.getOrElse(key, 0)
      val lodesCount = lodesMap.getOrElse(key, 0)
      csvWriter.write("\"" + src + "\"", "\"" + dst + "\"", beamCount, lodesCount)
    }
    csvWriter.close()
  }

  private def fixBlockGroupId(bgId: String) = bgId.length match {
    case 11 => "0" + bgId
    case _  => bgId
  }

  private def getLodesHomeWorkCounts(pathToLodes: String): Seq[(String, String, Int)] = {
    def mapper(rec: util.Map[String, String]): (String, String, Int) = {
      val originBlockGroupId = fixBlockGroupId(rec.get("O_bg"))
      val destBlockGroupId = rec.get("D_bg")
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

  def getLodesHomeWorkCountsMap(
    tazMap: TAZTreeMap,
    geoData: Array[(GeoAttribute, Coord)],
    lodesHomeToWorkCount: Seq[(String, String, Int)]
  ): Map[(Id[TAZ], Id[TAZ]), Int] = {
    val geoToTazMap = geoData.map {
      case (geoAttr, coord) =>
        geoAttr.censusBlockGroup -> tazMap.getTAZ(coord)
    }.toMap

    val result = mutable.Map.empty[(Id[TAZ], Id[TAZ]), Int]
    lodesHomeToWorkCount.foreach {
      case (origin, destination, cnt) =>
        (geoToTazMap.get(origin), geoToTazMap.get(destination)) match {
          case (Some(origTaz), Some(destTaz)) =>
            val key = (origTaz.tazId, destTaz.tazId)
            result.update(key, result.getOrElse(key, 0) + cnt)
          case _ =>
        }
    }
    println(s"lodes: ${result.size}")
    println(
      s"lodesHomeToWorkCount: ${lodesHomeToWorkCount.size}. Found ratio: ${result.size.toDouble / lodesHomeToWorkCount.size}"
    )
    result.toMap
  }

  private def getBeamHomeWorkTAZ(
    tazMap: TAZTreeMap,
    homeToWork: Seq[((Coord, Coord), Int)]
  ): Map[(Id[TAZ], Id[TAZ]), Int] = {

    val beamTazMap = mutable.Map.empty[(Id[TAZ], Id[TAZ]), Int]
    homeToWork.foreach {
      case ((origin, destination), cnt) =>
        val origTaz: TAZ = tazMap.getTAZ(origin)
        val destTaz: TAZ = tazMap.getTAZ(destination)
        val key = (origTaz.tazId, destTaz.tazId)
        beamTazMap.update(key, beamTazMap.getOrElse(key, 0) + cnt)
    }
    println(s"homeToWork: ${homeToWork.size}, beamTazMap: ${beamTazMap.size}")
    beamTazMap.toMap
  }

}
