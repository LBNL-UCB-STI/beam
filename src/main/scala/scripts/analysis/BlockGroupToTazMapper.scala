package scripts.analysis

import beam.sim.common.GeoUtils
import beam.utils.ProfilingUtils
import beam.utils.csv.CsvWriter
import scripts.ctpp.models.ResidenceToWorkplaceFlowGeography
import scripts.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData}
import scripts.ctpp.readers.flow.HouseholdIncomeTableReader
import scripts.synthpop.SimpleScenarioGenerator.getBlockGroupToTazs
import beam.utils.data.synthpop.models.Models.{BlockGroupGeoId, TazGeoId}
import beam.utils.data.synthpop.{GeoService, GeoServiceInputParam}

import scala.util.Random

object BlockGroupToTazMapper {
  def quoteCsv(s: String): String = "\"" + s + "\""

  def main(args: Array[String]): Unit = {
    require(
      args.length == 4,
      "Expect 4 args. First argument should be the path to CTPP directory. " +
      "The second argument is the path to Shape/TAZ directory. " +
      "The third argument is the path to Shape/BlockGroup directory" +
      "The fourth argument is the path to the OSM map file .pbf"
    )

    val pathToData = PathToData(args(0))
    // 34 - New Jersey
    // 36 - New York
    val databaseInfo = CTPPDatabaseInfo(pathToData, Set("34", "36"))
    val readData =
      new HouseholdIncomeTableReader(databaseInfo, ResidenceToWorkplaceFlowGeography.`TAZ To TAZ`)
        .read()
        .toVector

    val pathToResultFile = "block_group_to_taz.csv"
    blockGroupToTazMapper(pathToResultFile, pathToTaz = args(1), pathToBlockGroup = args(2), pathToOsm = args(3))
  }

  private def blockGroupToTazMapper(
    pathToResult: String,
    pathToTaz: String,
    pathToBlockGroup: String,
    pathToOsm: String
  ): Unit = {
    val geoUtils: GeoUtils = new GeoUtils {
      override def localCRS: String = "epsg:32118"
    }

    val geoSvc: GeoService = new GeoService(
      GeoServiceInputParam(pathToTaz, pathToBlockGroup, pathToOsm),
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
      blockGroupToToTazs.foreach { case (blockGroupId, xs) =>
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
