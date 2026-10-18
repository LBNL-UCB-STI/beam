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

import java.nio.file.{Files, Paths}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.seqAsJavaListConverter

object ActivitySimZarrWriter extends LazyLogging {

  def writeToZarr(
    filePath: String,
    skimData: Iterator[ExcerptData],
    geoUnits: Seq[String],
    provenanceMetadata: Option[SkimsProvenanceMetadata] = None
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

    logger.info(s"pathTypeToMatrixData keys: ${pathTypeToMatrixData.keys.map(_.toString).toSeq.sorted.mkString(", ")}")

    // Check if geoUnits are 1-based continuous integers
    try {
      val integerIds = geoUnits.map(_.toInt)
      val sortedInds = integerIds.sorted
      if (!(sortedInds == integerIds)) {
        if (sortedInds == (1 to geoUnits.size).toList) {
          logger.error("Zone IDs are 1 based but out of order! This is likely a bug")
        } else if (sortedInds == geoUnits.indices.toList) {
          logger.info("Zone IDs are 0 based contiguous already!")
        } else {
          logger.warn(f"Zone IDs are arbitrary numbers ${geoUnits.take(5)}. This may be a bug")
        }
      }

    } catch {
      case _: NumberFormatException =>
        logger.info(
          f"TAZ Ids contain arbitrary strings like ${geoUnits.take(5)}. Using the order they appear in the input shapefile"
        )
    }

    // Build appropriate mapping based on format
    val geoUnitMapping = geoUnits.zipWithIndex.toMap

    // Create 0-based TAZ IDs for coordinates, allowing ActivitySim to skip re-indexing
    val coordArray = geoUnits.indices.toArray // [0, 1, 2, ..., n-1]
    val zoneNumbers: Array[Int] = SkimsProvenanceMetadata.buildZoneNumbers(geoUnits)

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
      .toSeq
      .sortBy(_._1)
      .map { case (k, v) => k -> (v.map(_.getValue(TRIPS)).sum, v.map(_.getValue(FAILURES)).sum) }
    logger.info(s"Total counts by path type: ${failuresAndSuccesses.mkString(", ")}")

    // --- Zarr Directory Store Implementation using com.bc.zarr ---
    val targetPath = Paths.get(filePath)
    if (Files.exists(targetPath)) {
      com.bc.zarr.ZarrUtils.deleteDirectoryTreeRecursively(targetPath)
    }

    val store = new FileSystemStore(targetPath)
    logger.info(s"Zarr Directory Store created/opened at: $filePath")

    var rootGroup: com.bc.zarr.ZarrGroup = null
    try {
      rootGroup = com.bc.zarr.ZarrGroup.create(store)
      logger.info("Root Zarr group created successfully")

      var dataset_count = 0
      val shape = Array[Int](geoUnits.size, geoUnits.size, timePeriods.size)
      val zeroOffset = Array[Int](0, 0, 0)
      val idx = new Index3D(shape)
      val canonicalNaN = SkimsProvenanceMetadata.canonicalizeFloat(Float.NaN)

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
        .fillValue(canonicalNaN)

      // Store indices as coordinates
      val originCoordParams = new com.bc.zarr.ArrayParams()
        .shape(geoUnits.size)
        .dataType(DataType.i4)
        .fillValue(-1)
      val originCoord = rootGroup.createArray("otaz", originCoordParams)
      originCoord.write(coordArray, Array(geoUnits.size), Array(0))

      // Similar for destination
      val destCoord = rootGroup.createArray("dtaz", originCoordParams)
      destCoord.write(coordArray, Array(geoUnits.size), Array(0))

      // Also write 1D int32 zone_number lookup matching OMX /lookup/zone_number
      val zoneNumberCoord = rootGroup.createArray("zone_number", originCoordParams)
      zoneNumberCoord.write(zoneNumbers, Array(geoUnits.size), Array(0))
      val zoneNumberAttrs = new java.util.LinkedHashMap[String, Object]()
      zoneNumberAttrs.put("_ARRAY_DIMENSIONS", java.util.Arrays.asList("otaz"))
      zoneNumberCoord.writeAttributes(zoneNumberAttrs)

      // For time periods, use indices
      val timeCoordParams = new com.bc.zarr.ArrayParams()
        .shape(timePeriods.size)
        .dataType(DataType.i4)
        .fillValue(-1)
      val timeCoord = rootGroup.createArray("time_period", timeCoordParams)
      timeCoord.write(Array(0, 1, 2, 3, 4), Array(timePeriods.size), Array(0))

      // Add labels as attributes in deterministic key order
      val timeAttrs = new java.util.LinkedHashMap[String, Object]()
      timeAttrs.put("_ARRAY_DIMENSIONS", java.util.Arrays.asList("time_period"))
      timeAttrs.put("labels", timePeriodNames.asJava)
      timeCoord.writeAttributes(timeAttrs)

      // Add _ARRAY_DIMENSIONS to coordinate arrays
      val originAttrs = new java.util.LinkedHashMap[String, Object]()
      originAttrs.put("_ARRAY_DIMENSIONS", java.util.Arrays.asList("otaz"))
      originCoord.writeAttributes(originAttrs)

      val destAttrs = new java.util.LinkedHashMap[String, Object]()
      destAttrs.put("_ARRAY_DIMENSIONS", java.util.Arrays.asList("dtaz"))
      destCoord.writeAttributes(destAttrs)

      logger.info(s"Grouped data has ${groupedData.size} unique path types and metrics combinations.")

      // Collect 2D (pathType_metric__timePeriod) logical slices to compute the exact same logical_matrices_sha256 as OMX
      val logical2DSlices = ArrayBuffer.empty[(String, Array[Int], Array[Float])]
      val shape2D = Array[Int](geoUnits.size, geoUnits.size)
      val n = geoUnits.size

      groupedData.toSeq.sortBy(_._1._1).foreach { case ((pathType, metrics), excerpts) =>
        val sortedMetrics = metrics.toSeq.sortBy(_.toString)
        logger.info(s"Processing path type: $pathType with metrics: ${sortedMetrics.mkString(", ")}")
        val metricToArray = sortedMetrics.map { metric =>
          val matrixName = s"${pathType}_${metric}"
          logger.debug(s"Creating dataset '$matrixName' with shape ${shape.mkString("x")}")
          dataset_count += 1

          val zarrArray = rootGroup.createArray(matrixName, arrayParams)

          // Create a NetcdfArray to fill data
          val netcdfArray = NetcdfArray.factory(ucar.ma2.DataType.FLOAT, shape)
          java.util.Arrays.fill(netcdfArray.getStorage.asInstanceOf[Array[Float]], canonicalNaN)

          (metric, (zarrArray, netcdfArray))
        }

        val metricToArrayMap = metricToArray.toMap
        val observedTimeIndices = scala.collection.mutable.SortedSet.empty[Int]

        // Sort excerpts deterministically by (row, column, timeIdx)
        val sortedExcerpts = excerpts
          .flatMap { excerptData =>
            for {
              row     <- geoUnitMapping.get(excerptData.originId)
              column  <- geoUnitMapping.get(excerptData.destinationId)
              timeIdx <- timePeriodLookup.get(excerptData.timePeriodString)
              if timeIdx >= 0
            } yield (row, column, timeIdx, excerptData)
          }
          .sortBy { case (r, c, t, _) => (r, c, t) }

        sortedExcerpts.foreach { case (row, column, timeIdx, excerptData) =>
          observedTimeIndices += timeIdx
          sortedMetrics.foreach { metric =>
            val (_, netcdfArray) = metricToArrayMap(metric)
            val rawValue = excerptData.getValue(metric).toFloat * getUnitConversion(metric)
            val value = SkimsProvenanceMetadata.canonicalizeFloat(rawValue)
            netcdfArray.setFloat(idx.set(row, column, timeIdx), value)
          }
        }

        metricToArray.foreach { case (metric, (zarrArray, netcdfArray)) =>
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

          observedTimeIndices.foreach { timeIdx =>
            val tpName = timePeriodNames(timeIdx)
            val slice2D = Array.fill[Float](n * n)(canonicalNaN)
            var r = 0
            while (r < n) {
              var c = 0
              while (c < n) {
                slice2D(r * n + c) = netcdfArray.getFloat(idx.set(r, c, timeIdx))
                c += 1
              }
              r += 1
            }
            logical2DSlices += ((s"${pathType}_${metric}__$tpName", shape2D, slice2D))
          }

          val attrs = new java.util.LinkedHashMap[String, Object]()
          attrs.put("_ARRAY_DIMENSIONS", java.util.Arrays.asList("otaz", "dtaz", "time_period"))
          attrs.put("measure", metric.toString)
          attrs.put("mode", pathType)
          attrs.put("timePeriods", timePeriodNames.toList.asJava)

          zarrArray.writeAttributes(attrs)

          logger.debug(s"Successfully wrote dataset and attributes for '${metric.toString}'")
        }
      }

      val logicalSha256 = SkimsProvenanceMetadata.computeLogicalMatricesSha256(
        zoneNumbers = zoneNumbers,
        sortedMatrices = logical2DSlices.sortBy(_._1).toSeq
      )

      val rootAttrsList = ArrayBuffer[(String, Object)](
        "beam_skims_schema_version" -> provenanceMetadata
          .map(_.beamSkimsSchemaVersion)
          .getOrElse(SkimsProvenanceMetadata.SchemaVersion),
        "logical_matrices_sha256" -> logicalSha256,
        "original_zone_ids"       -> geoUnits.asJava,
        "taz_count"               -> Integer.valueOf(geoUnits.size),
        "taz_format"              -> "arbitrary"
      )
      provenanceMetadata.foreach { meta =>
        rootAttrsList += ("bifurcate_tolls"       -> java.lang.Boolean.valueOf(meta.bifurcateTolls))
        rootAttrsList += ("generate_return_trips" -> java.lang.Boolean.valueOf(meta.generateReturnTrips))
        rootAttrsList += ("modes_built"           -> meta.modesBuilt)
        rootAttrsList += ("peak_hours"            -> meta.peakHours)
        rootAttrsList += ("router_type"           -> meta.routerType)
        rootAttrsList += ("skims_geo_type"        -> meta.skimsGeoType)
        rootAttrsList += ("skims_kind"            -> meta.skimsKind)
      }
      val rootAttrs = new java.util.LinkedHashMap[String, Object]()
      rootAttrsList.sortBy(_._1).foreach { case (k, v) => rootAttrs.put(k, v) }
      rootGroup.writeAttributes(rootAttrs)

      logger.info(
        s"Zarr Directory Store written successfully with $dataset_count datasets and logical hash $logicalSha256."
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
  private[skim] val activitySimMatrixData = IndexedSeq(
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
      Set(BIKE, WALK),
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
