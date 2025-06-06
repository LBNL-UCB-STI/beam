package beam.router.skim.urbansim

import beam.router.skim.ActivitySimMetric._
import beam.router.skim.ActivitySimPathType._
import beam.router.skim.ActivitySimSkimmer.ExcerptData
import beam.router.skim.{ActivitySimMetric, ActivitySimPathType, ActivitySimTimeBin}
import beam.router.skim.ActivitySimTimeBin._
import com.bc.zarr.DataType
import com.typesafe.scalalogging.LazyLogging
import com.bc.zarr.storage.FileSystemStore

import java.nio.file.Paths

object ActivitySimZarrWriter extends LazyLogging {

  def writeToZarr(
    filePath: String,
    skimData: Iterator[ExcerptData],
    geoUnits: Seq[String]
  ): Unit = try {
    logger.info(s"Starting writeToZarr with filePath: $filePath")

    // Build a map from path type to MatrixData for quick lookup
    val pathTypeToMatrixData: Map[ActivitySimPathType, MatrixData] =
      activitySimMatrixData.flatMap(md => md.pathTypes.map(_ -> md)).toMap

    val geoUnitMapping = geoUnits.zipWithIndex.toMap
    val timePeriods = ActivitySimTimeBin.values.toIndexedSeq // Keep as enum values for index lookup
    val timePeriodNames = timePeriods.map(_.entryName)

    val groupedData = skimData.toSeq.groupBy { excerptData =>
      val pathType = excerptData.pathType match { // Standardize TNC names with fleet suffix
        case rideHailMode @ (TNC_SINGLE | TNC_SHARED) =>
          f"${rideHailMode.toString}_${excerptData.fleetName.toUpperCase}"
        case _ => excerptData.pathType.toString
      }
      (
        pathType,
        pathTypeToMatrixData.get(excerptData.pathType).map(_.metrics).getOrElse(Set.empty[ActivitySimMetric])
      )
    }

    // --- Zarr Directory Store Implementation using com.bc.zarr ---

    val store = new FileSystemStore(Paths.get(filePath))
    logger.info(s"Zarr Directory Store created/opened at: $filePath")

    var rootGroup: com.bc.zarr.ZarrGroup = null
    try {
      rootGroup = com.bc.zarr.ZarrGroup.create(store)
      logger.info("Root Zarr group created successfully")

      var dataset_count = 0

      groupedData.par.foreach { case ((pathType, metrics), excerpts) =>
        metrics.par.foreach { metric =>
          val matrixName = s"${pathType}_${metric}"
          val shape = Array[Int](geoUnits.size, geoUnits.size, timePeriods.size)
          logger.debug(s"Creating dataset '$matrixName' with shape ${shape.mkString("x")}")
          dataset_count += 1

          val compressor = com.bc.zarr.CompressorFactory.create(
            "zlib"
          )
//          val compressor = com.bc.zarr.CompressorFactory.create(
//            "blosc",
//            "cname",
//            "zstd",
//            "clevel",
//            "5",
//            "shuffle",
//            "1"
//          )
          val chunkShape = Array[Int](shape(0), shape(1), 1)

          val arrayParams = new com.bc.zarr.ArrayParams()
            .shape(shape: _*)
            .chunks(chunkShape: _*)
            .dataType(DataType.f4)
            .compressor(compressor)
            .fillValue(Float.NaN)

          val zarrArray = rootGroup.createArray(matrixName, arrayParams)

          excerpts.foreach { excerptData =>
            for {
              row    <- geoUnitMapping.get(excerptData.originId)
              column <- geoUnitMapping.get(excerptData.destinationId)
              timeBinOpt = ActivitySimTimeBin.values.find(_.entryName == excerptData.timePeriodString)
              if timeBinOpt.isDefined
              timeIdx = timePeriods.indexOf(timeBinOpt.get)
              if timeIdx >= 0
            } {
              val offset = Array[Int](row, column, timeIdx)
              val dataShape = Array[Int](1, 1, 1) // Single value shape
              val value = excerptData.getValue(metric).toFloat * getUnitConversion(metric)
              val javaFloatArray = Array[Float](value) // Create primitive float array directly
              try {
                zarrArray.write(javaFloatArray, dataShape, offset)
              } catch {
                case e: java.lang.RuntimeException =>
                  val value = excerptData.getValue(metric)
                  val conversion = getUnitConversion(metric)
                  logger.info(s"Writing value: $value (${value.getClass.getName}) with conversion: $conversion")
                  logger.error(s"Failed to initialize data for $matrixName at offset $offset: ${e.getMessage}", e)
              }
            }
          }

          val attrs = zarrArray.getAttributes()
          attrs.put("mode", pathType)
          attrs.put("measure", metric.toString)
          attrs.put("timePeriods", timePeriodNames.toList)
          // No attrs.write() needed

          logger.debug(s"Successfully wrote dataset and attributes for '$matrixName'")
        }
      } // ADD SECOND BLOCK HERE

      logger.info(
        s"Zarr Directory Store written successfully with $dataset_count datasets."
      ) // Report actual dataset count

    } finally {
      // No close method needed for rootGroup
    }

  } catch {
    case e: Exception =>
      logger.error(s"Unexpected error while writing Zarr file: ${e.getMessage}", e)
      throw new RuntimeException(
        s"Unexpected error while writing Zarr file: ${e.getMessage}. Error type: ${e.getClass.getSimpleName}",
        e
      )
  }

  private def getUnitConversion(metric: ActivitySimMetric): Float = {
    metric match {
      case DIST | DDIST => 1f / 1609.34f
      case _            => 1f
    }
  }

  // --- Definitions copied from ActivitySimOmxWriter ---
  // Contains data types that is used by ActivitySim: path types, time bins and metrics
  // @param pathTypes possible path types
  // @param timeBins we don't use time bins now because data can be defined for all time bins for all path types
  //                 that Beam produces
  // @param metrics possible metrics
  case class MatrixData(
    pathTypes: Set[ActivitySimPathType],
    timeBins: Set[ActivitySimTimeBin],
    metrics: Set[ActivitySimMetric]
  )

  // Configuration for which metrics are expected for which path types for ActivitySim export
  private val activitySimMatrixData = IndexedSeq(
    MatrixData(
      Set(DRV_COM_WLK, DRV_EXP_WLK, DRV_HVY_WLK, WLK_COM_DRV, WLK_EXP_DRV, WLK_HVY_DRV),
      ActivitySimTimeBin.values.toSet,
      Set(TOTIVT, FAR, XWAIT, KEYIVT, IWAIT, DTIM, BOARDS, DDIST, WAUX, TRIPS, FAILURES)
    ),
    MatrixData(
      Set(DRV_LOC_WLK, WLK_LOC_DRV),
      ActivitySimTimeBin.values.toSet,
      Set(TOTIVT, FAR, XWAIT, IWAIT, DTIM, BOARDS, DDIST, WAUX, TRIPS, FAILURES)
    ),
    MatrixData(
      Set(DRV_LRF_WLK, WLK_LRF_DRV),
      ActivitySimTimeBin.values.toSet,
      Set(TOTIVT, FERRYIVT, FAR, XWAIT, KEYIVT, DTIM, IWAIT, BOARDS, DDIST, WAUX, TRIPS, FAILURES)
    ),
    MatrixData(
      Set(HOV2TOLL, HOV3TOLL, SOVTOLL),
      ActivitySimTimeBin.values.toSet,
      Set(BTOLL, VTOLL, TIME, DIST)
    ),
    MatrixData(
      Set(BIKE),
      ActivitySimTimeBin.values.toSet,
      Set(TIME, DIST)
    ),
    MatrixData(Set(HOV2, HOV3, SOV), ActivitySimTimeBin.values.toSet, Set(BTOLL, TIME, DIST, TRIPS, FAILURES)),
    MatrixData(
      Set(WLK_COM_WLK, WLK_EXP_WLK, WLK_HVY_WLK),
      ActivitySimTimeBin.values.toSet,
      Set(TOTIVT, FAR, XWAIT, KEYIVT, IWAIT, BOARDS, WAUX, TRIPS, FAILURES)
    ),
    MatrixData(
      Set(WLK_LOC_WLK),
      ActivitySimTimeBin.values.toSet,
      Set(TOTIVT, FAR, XWAIT, IWAIT, BOARDS, WAUX, TRIPS, FAILURES)
    ),
    MatrixData(
      Set(WLK_LRF_WLK),
      ActivitySimTimeBin.values.toSet,
      Set(TOTIVT, FERRYIVT, FAR, XWAIT, KEYIVT, IWAIT, BOARDS, WAUX, TRIPS, FAILURES)
    ),
    MatrixData(
      Set(WLK_TRN_WLK),
      Set(PM_PEAK, MIDDAY, AM_PEAK),
      Set(WACC, IVT, XWAIT, IWAIT, WEGR, WAUX, TRIPS, FAILURES)
    ),
    MatrixData(
      Set(TNC_SINGLE, TNC_SHARED),
      ActivitySimTimeBin.values.toSet,
      Set(IWAIT, TOTIVT, DDIST, FAR, TRIPS, FAILURES)
    )
  )
  // --- End of definitions copied from ActivitySimOmxWriter ---
}
