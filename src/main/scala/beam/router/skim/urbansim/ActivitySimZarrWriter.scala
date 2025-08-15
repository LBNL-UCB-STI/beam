package beam.router.skim.urbansim

import beam.router.skim.ActivitySimMetric._
import beam.router.skim.ActivitySimPathType._
import beam.router.skim.ActivitySimSkimmer.ExcerptData
import beam.router.skim.{ActivitySimMetric, ActivitySimPathType, ActivitySimTimeBin}
import beam.router.skim.ActivitySimTimeBin._
import com.bc.zarr.DataType
import com.typesafe.scalalogging.LazyLogging
import com.bc.zarr.storage.FileSystemStore
import ucar.ma2.{Index3D, Array => NetcdfArray}

import java.nio.file.Paths
import scala.jdk.CollectionConverters.seqAsJavaListConverter

object ActivitySimZarrWriter extends LazyLogging {

  def writeToZarr(
    filePath: String,
    skimData: Iterator[ExcerptData],
    geoUnits: Seq[String]
  ): Unit = try {
    logger.info(s"Starting writeToZarr with filePath: $filePath")

    // Build a map from path type to MatrixData for quick lookup
    val pathTypeToMatrixData: Map[ActivitySimPathType, MatrixData] = (
      for {
        data     <- activitySimMatrixData
        pathType <- data.pathTypes
        limitedData = data.copy(metrics = data.metrics & ExcerptData.supportedActivitySimMetric)
      } yield pathType -> limitedData
    ).toMap

    logger.info(s"pathTypeToMatrixData keys: ${pathTypeToMatrixData.keys.map(_.toString).mkString(", ")}")

    // Check if geoUnits are 1-based continuous integers
    val isActivitySimFormat =
      try {
        val sorted = geoUnits.map(_.toInt).sorted
        sorted == (1 to geoUnits.size).toList
      } catch {
        case _: NumberFormatException => false // Contains non-numeric IDs like "10091C"
      }

    // Build appropriate mapping based on format
    val geoUnitMapping = if (isActivitySimFormat) {
      // For ActivitySim format: map TAZ ID to its value minus 1
      geoUnits.map { tazId =>
        tazId -> (tazId.toInt - 1)
      }.toMap
    } else {
      // For arbitrary TAZ IDs: map to position in list
      geoUnits.zipWithIndex.toMap
    }
    // Create ActivitySim TAZ IDs (1-based)
    val coordArray = if (isActivitySimFormat) {
      geoUnits.map(_.toInt).sorted.toArray // [1, 2, 3, ..., 1454]
    } else {
      geoUnits.indices.toArray // [0, 1, 2, ..., n-1] for backwards compatibility
    }

    val timePeriods = ActivitySimTimeBin.values.toIndexedSeq // Keep as enum values for index lookup
    val timePeriodNames = timePeriods.map(_.entryName)
    val timePeriodLookup: Map[String, Int] =
      timePeriods.map(_.entryName).zipWithIndex.toMap

    val skimDataSeq = skimData.toSeq

    logger.info(s"Total skim data entries: ${skimDataSeq.size}")

    val groupedData = skimDataSeq.groupBy { excerptData =>
      val pathType = excerptData.pathType match {
        case rideHailMode @ (TNC_SINGLE | TNC_SHARED) =>
          f"${rideHailMode.toString}_${excerptData.fleetName.toUpperCase}"
        case _ => excerptData.pathType.toString
      }
      (
        pathType,
        pathTypeToMatrixData.get(excerptData.pathType).map(_.metrics).getOrElse(Set.empty[ActivitySimMetric])
      )
    }

    val failuresAndSuccesses = skimDataSeq
      .groupBy(_.pathType.toString)
      .mapValues(v => (v.map(_.getValue(TRIPS)).sum, v.map(_.getValue(FAILURES)).sum))
    logger.info(s"Total counts by path type: ${failuresAndSuccesses.mkString(", ")}")

    // --- Zarr Directory Store Implementation using com.bc.zarr ---

    val store = new FileSystemStore(Paths.get(filePath))
    logger.info(s"Zarr Directory Store created/opened at: $filePath")

    var rootGroup: com.bc.zarr.ZarrGroup = null
    try {
      rootGroup = com.bc.zarr.ZarrGroup.create(store)
      logger.info("Root Zarr group created successfully")
      val rootAttrs = rootGroup.getAttributes
      rootAttrs.put("original_zone_ids", geoUnits.asJava)
      rootAttrs.put("taz_format", if (isActivitySimFormat) "activitysim" else "arbitrary")
      rootGroup.writeAttributes(rootAttrs)

      var dataset_count = 0
      val shape = Array[Int](geoUnits.size, geoUnits.size, timePeriods.size)
      val zeroOffset = Array[Int](0, 0, 0)
      val idx = new Index3D(shape)

      val compressor = com.bc.zarr.CompressorFactory.create(
        "blosc",
        "cname",
        "zstd",
        "clevel",
        "5",
        "shuffle",
        "1"
      )
      val chunkShape = Array[Int](shape(0), shape(1), shape(2))

      val arrayParams = new com.bc.zarr.ArrayParams()
        .shape(shape: _*)
        .chunks(chunkShape: _*)
        .dataType(DataType.f4)
        .compressor(compressor)
        .fillValue(Float.NaN)

      // Store indices as coordinates
      val originCoordParams = new com.bc.zarr.ArrayParams()
        .shape(geoUnits.size)
        .dataType(DataType.i4)
        .fillValue(-1)
      val originCoord = rootGroup.createArray("otaz", originCoordParams)
      originCoord.write(coordArray, Array(geoUnits.size), Array(0)) // Write 1, 2, 3, ...

      // Similar for destination
      val destCoord = rootGroup.createArray("dtaz", originCoordParams)
      destCoord.write(coordArray, Array(geoUnits.size), Array(0)) // Write 1, 2, 3, ...

      // For time periods, use indices
      val timeCoordParams = new com.bc.zarr.ArrayParams()
        .shape(timePeriods.size)
        .dataType(DataType.i4)
        .fillValue(-1)
      val timeCoord = rootGroup.createArray("time_period", timeCoordParams)
      timeCoord.write(Array(0, 1, 2, 3, 4), Array(timePeriods.size), Array(0))

      // Add labels as attributes
      val timeAttrs = timeCoord.getAttributes()
      timeAttrs.put("labels", timePeriodNames.asJava)
      timeAttrs.put("_ARRAY_DIMENSIONS", java.util.Arrays.asList("time_period"))
      timeCoord.writeAttributes(timeAttrs)

      // Add _ARRAY_DIMENSIONS to coordinate arrays (optional but good practice)
      val originAttrs = originCoord.getAttributes()
      originAttrs.put("_ARRAY_DIMENSIONS", java.util.Arrays.asList("otaz"))
      originCoord.writeAttributes(originAttrs)

      val destAttrs = destCoord.getAttributes()
      destAttrs.put("_ARRAY_DIMENSIONS", java.util.Arrays.asList("dtaz"))
      destCoord.writeAttributes(destAttrs)

      logger.info(s"Grouped data has ${groupedData.size} unique path types and metrics combinations.")

      groupedData.foreach { case ((pathType, metrics), excerpts) =>
        logger.info(s"Processing path type: $pathType with metrics: ${metrics.mkString(", ")}")
        val metricToArray = metrics.map { metric =>
          val matrixName = s"${pathType}_${metric}"
          logger.debug(s"Creating dataset '$matrixName' with shape ${shape.mkString("x")}")
          dataset_count += 1

          val zarrArray = rootGroup.createArray(matrixName, arrayParams)

          // Create a NetcdfArray to fill data
          val netcdfArray = NetcdfArray.factory(ucar.ma2.DataType.FLOAT, shape)
          java.util.Arrays.fill(netcdfArray.getStorage.asInstanceOf[Array[Float]], Float.NaN)

          (metric, (zarrArray, netcdfArray))
        }.toMap

        excerpts.foreach { excerptData =>
          for {
            row     <- geoUnitMapping.get(excerptData.originId)
            column  <- geoUnitMapping.get(excerptData.destinationId)
            timeIdx <- timePeriodLookup.get(excerptData.timePeriodString)
            if timeIdx >= 0
          } {
            metricToArray.foreach { case (metric, (_, netcdfArray)) =>
              val value = excerptData.getValue(metric).toFloat * getUnitConversion(metric)
              netcdfArray.setFloat(idx.set(row, column, timeIdx), value)
            }
          }
        }

        metricToArray.foreach { case (metric, (zarrArray, netcdfArray)) =>
          {
            try {
              zarrArray.write(netcdfArray.get1DJavaArray(netcdfArray.getDataType), shape, zeroOffset)
            } catch {
              case e: java.lang.NoSuchMethodError =>
                val conversion = getUnitConversion(metric)
                logger.info(s"Writing value ($metric) with conversion: $conversion")
                logger.error(
                  s"Failed to initialize data for ${metric.toString} at offset ${zeroOffset
                    .mkString("Array(", ", ", ")")}: ${e.getMessage}",
                  e
                )
            }
          }

          val attrs = zarrArray.getAttributes()
          attrs.put("mode", pathType)
          attrs.put("measure", metric.toString)
          attrs.put("timePeriods", timePeriodNames.toList.asJava)
          attrs.put("_ARRAY_DIMENSIONS", java.util.Arrays.asList("otaz", "dtaz", "time_period"))

          zarrArray.writeAttributes(attrs)

          logger.debug(s"Successfully wrote dataset and attributes for '${metric.toString}'")
        }
      }

      logger.info(
        s"Zarr Directory Store written successfully with $dataset_count datasets."
      )

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
