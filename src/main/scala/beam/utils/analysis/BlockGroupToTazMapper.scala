package beam.utils.analysis

import beam.sim.common.GeoUtils
import beam.utils.ProfilingUtils
import beam.utils.csv.CsvWriter
import beam.utils.data.ctpp.models.ResidenceToWorkplaceFlowGeography
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData}
import beam.utils.data.ctpp.readers.flow.HouseholdIncomeTableReader
import beam.utils.data.synthpop.SimpleScenarioGenerator.getBlockGroupToTazs
import beam.utils.data.synthpop.models.Models.{BlockGroupGeoId, TazGeoId}
import beam.utils.data.synthpop.{GeoService, GeoServiceInputParam}

import scala.util.Random

object BlockGroupToTazMapper {
  def quoteCsv(s: String): String = "\"" + s + "\""

  def main(args: Array[String]): Unit = {
    val pathToData = PathToData("D:/Work/beam/CTPP/")
    // 34 - New Jersey
    // 36 - New York
    val databaseInfo = CTPPDatabaseInfo(pathToData, Set("34", "36"))
    val readData =
      new HouseholdIncomeTableReader(databaseInfo, ResidenceToWorkplaceFlowGeography.`TAZ To TAZ`)
        .read()
        .toVector

    blockGroupToTazMapper("block_group_to_taz.csv")
  }

  private def blockGroupToTazMapper(pathToResult: String): Unit = {
    val geoUtils: GeoUtils = new GeoUtils {
      override def localCRS: String = "epsg:32118"
    }

    val geoSvc: GeoService = new GeoService(
      GeoServiceInputParam(
        "D:/Work/beam/NewYork/input/Shape/TAZ/",
        "D:/Work/beam/NewYork/input/Shape/BlockGroup/",
        "D:/Work/beam/NewYork/input/OSM/newyork-14-counties-incomplete.osm.pbf"
      ),
      Set.empty,
      geoUtils
    )
    val blockGroupToToTazs: Map[BlockGroupGeoId, List[(TazGeoId, Double)]] = ProfilingUtils.timed(
      s"getBlockGroupToTazs for blockGroupGeoIdToGeom ${geoSvc.blockGroupGeoIdToGeom.size} and tazGeoIdToGeom ${geoSvc.tazGeoIdToGeom.size}",
      x => println(x)
    ) {
      getBlockGroupToTazs(geoSvc.blockGroupGeoIdToGeom, geoSvc.tazGeoIdToGeom)
    }
    println(s"blockGroupToToTazs: ${blockGroupToToTazs.size}")
    val maxCount = blockGroupToToTazs.map(_._2.length).max
    println(s"maxCount: $maxCount")

    val rndSeed = 42

    val csvWriter = new CsvWriter(pathToResult, Array("block_group_id", "taz_id"))
    try {
      blockGroupToToTazs.foreach {
        case (blockGroupId, xs) =>
          val (tazId, _) = new Random(rndSeed).shuffle(xs).head
          // As GEOID field in shape file
          val blockGroupIdStr =
            s"${blockGroupId.state.value}${blockGroupId.county.value}${blockGroupId.tract}${blockGroupId.blockGroup}"
          // As GEOID10 field in shape file
          val tazIdStr = s"${tazId.state.value}${tazId.county.value}${tazId.taz}"
          csvWriter.write(quoteCsv(blockGroupIdStr), quoteCsv(tazIdStr))
      }
    } finally {
      csvWriter.close()
    }
  }
}
