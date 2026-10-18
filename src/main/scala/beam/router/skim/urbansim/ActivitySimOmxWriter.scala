package beam.router.skim.urbansim

import beam.router.skim.ActivitySimMetric._
import beam.router.skim.ActivitySimPathType._
import beam.router.skim.ActivitySimSkimmer.ExcerptData
import beam.router.skim.ActivitySimTimeBin._
import beam.router.skim.{ActivitySimMetric, ActivitySimPathType, ActivitySimTimeBin}
import beam.utils.csv.CsvWriter
import com.typesafe.scalalogging.LazyLogging
import omx.hdf5.HDF5Loader
import omx.{OmxFile, OmxLookup, OmxMatrix}

import java.nio.file.{Files, Paths}
import scala.collection.mutable.ArrayBuffer

/**
  * @author Dmitry Openkov
  */
object ActivitySimOmxWriter extends LazyLogging {

  def writeToOmx(
    filePath: String,
    skimData: Iterator[ExcerptData],
    geoUnits: Seq[String],
    provenanceMetadata: Option[SkimsProvenanceMetadata] = None
  ): Unit = try {
    logger.info(s"Starting writeToOmx with filePath: $filePath")
    logger.info(s"HDF5 library preparation starting...")
    HDF5Loader.prepareHdf5Library()
    logger.info(s"HDF5 library prepared successfully")

    val pathTypeToMatrixData: Map[ActivitySimPathType, MatrixData] = (
      for {
        data     <- activitySimMatrixData
        pathType <- data.pathTypes
        limitedData = data.copy(metrics = data.metrics & ExcerptData.supportedActivitySimMetric)
      } yield pathType -> limitedData
    ).toMap
    logger.info(s"Matrix data map created with ${pathTypeToMatrixData.size} entries")

    val targetPath = Paths.get(filePath)
    Option(targetPath.getParent).foreach(parent => Files.createDirectories(parent))
    if (Files.exists(targetPath)) {
      Files.delete(targetPath)
    }

    val n = geoUnits.size
    val shape: Array[Int] = Array(n, n)
    logger.info(s"Creating new OmxFile instance for path: $filePath with shape ${n}x${n}")
    val omxFile = new OmxFile(filePath)
    omxFile.openNew(shape)

    val geoUnitMapping = geoUnits.zipWithIndex.toMap
    val zoneNumbers: Array[Int] = SkimsProvenanceMetadata.buildZoneNumbers(geoUnits)

    // Group the data by (pathType, timePeriod) in a deterministic manner
    val groupedData = skimData.toSeq.groupBy { excerptData =>
      val pathType = excerptData.pathType match {
        case rideHailMode @ (TNC_SINGLE | TNC_SHARED) =>
          f"${rideHailMode.toString}_${excerptData.fleetName.toUpperCase}"
        case _ => excerptData.pathType.toString
      }
      (
        pathType,
        excerptData.timePeriodString,
        pathTypeToMatrixData.get(excerptData.pathType).map(_.metrics).getOrElse(Set.empty[ActivitySimMetric])
      )
    }

    case class PreparedOmxMatrix(
      matrixName: String,
      pathType: String,
      timePeriod: String,
      metric: String,
      values2D: Array[Array[Float]],
      flatValues: Array[Float]
    )

    val preparedMatricesBuffer = ArrayBuffer.empty[PreparedOmxMatrix]
    val canonicalNaN = SkimsProvenanceMetadata.canonicalizeFloat(Float.NaN)

    for {
      ((pathType, timePeriod, metrics), excerpts) <- groupedData.toSeq.sortBy { case ((pt, tp, _), _) => (pt, tp) }
      metric                                      <- metrics.toSeq.sortBy(_.toString)
    } {
      val matrixName = s"${pathType}_${metric}__$timePeriod"
      val valuesFloat = Array.fill[Float](n, n)(canonicalNaN)
      val flatValues = Array.fill[Float](n * n)(canonicalNaN)
      val unitConv = getUnitConversion(metric)

      // Sort excerpts deterministically by (originIdx, destIdx) before populating
      val sortedExcerpts = excerpts
        .flatMap { excerptData =>
          for {
            row    <- geoUnitMapping.get(excerptData.originId)
            column <- geoUnitMapping.get(excerptData.destinationId)
          } yield (row, column, excerptData)
        }
        .sortBy { case (r, c, _) => (r, c) }

      sortedExcerpts.foreach { case (row, column, excerptData) =>
        val rawVal = excerptData.getValue(metric).toFloat * unitConv
        val canonicalVal = SkimsProvenanceMetadata.canonicalizeFloat(rawVal)
        valuesFloat(row)(column) = canonicalVal
        flatValues(row * n + column) = canonicalVal
      }

      preparedMatricesBuffer += PreparedOmxMatrix(
        matrixName = matrixName,
        pathType = pathType,
        timePeriod = timePeriod,
        metric = metric.toString,
        values2D = valuesFloat,
        flatValues = flatValues
      )
    }

    val sortedMatrices = preparedMatricesBuffer.sortBy(_.matrixName).toSeq
    val logicalSha256 = SkimsProvenanceMetadata.computeLogicalMatricesSha256(
      zoneNumbers = zoneNumbers,
      sortedMatrices = sortedMatrices.map(m => (m.matrixName, shape, m.flatValues))
    )

    try {
      // Attach root provenance attributes via OmxFile (extends AttributedElement)
      val rootAttributes = ArrayBuffer[(String, Any)](
        "beam_skims_schema_version" -> provenanceMetadata
          .map(_.beamSkimsSchemaVersion)
          .getOrElse(SkimsProvenanceMetadata.SchemaVersion),
        "logical_matrices_sha256" -> logicalSha256,
        "taz_count"               -> Integer.valueOf(n)
      )
      provenanceMetadata.foreach { meta =>
        rootAttributes += ("bifurcate_tolls"       -> meta.bifurcateTolls.toString)
        rootAttributes += ("generate_return_trips" -> meta.generateReturnTrips.toString)
        rootAttributes += ("modes_built"           -> meta.modesBuilt)
        rootAttributes += ("peak_hours"            -> meta.peakHours)
        rootAttributes += ("router_type"           -> meta.routerType)
        rootAttributes += ("skims_geo_type"        -> meta.skimsGeoType)
        rootAttributes += ("skims_kind"            -> meta.skimsKind)
      }
      rootAttributes.sortBy(_._1).foreach { case (attrName, attrVal) =>
        omxFile.setAttribute(attrName, attrVal)
      }

      // Add /lookup/zone_number
      if (n > 0) {
        val zoneNumberLookup = new OmxLookup.OmxIntLookup("zone_number", zoneNumbers, Integer.valueOf(-1))
        omxFile.addLookup(zoneNumberLookup)
      }

      // Add /data/<matrixName> matrices in sorted order
      sortedMatrices.foreach { m =>
        val matrix = new OmxMatrix.OmxFloatMatrix(m.matrixName, m.values2D, java.lang.Float.valueOf(-1.0f))
        matrix.setAttribute("measure", m.metric)
        matrix.setAttribute("mode", m.pathType)
        matrix.setAttribute("timePeriod", m.timePeriod)
        omxFile.addMatrix(matrix)
      }

      logger.info(s"Saving OMX file with ${sortedMatrices.size} matrices and logical hash $logicalSha256...")
      omxFile.save()
      logger.info("OMX file saved successfully")
    } finally {
      omxFile.close()
      logger.info("OMX file closed")
    }

    // Write geo unit mapping as before
    CsvWriter(filePath + ".mapping", "zone_id").writeAllAndClose(geoUnits.map(Seq(_)))
  } catch {
    case e: java.io.FileNotFoundException =>
      e.printStackTrace()
      throw new RuntimeException(s"Failed to create or access file at path: $filePath. Error: ${e.getMessage}", e)
    case e: java.io.IOException =>
      e.printStackTrace()
      throw new RuntimeException(s"IO error while writing to OMX file: ${e.getMessage}", e)
    case e: IllegalArgumentException =>
      throw new RuntimeException(s"Invalid argument provided: ${e.getMessage}", e)
    case e: NoSuchElementException =>
      throw new RuntimeException(s"Missing required data: ${e.getMessage}", e)
    case e: OutOfMemoryError =>
      throw new RuntimeException(s"Insufficient memory to process the matrix data.", e)
    case e: Exception =>
      throw new RuntimeException(
        s"Unexpected error while writing OMX file: ${e.getMessage}. Error type: ${e.getClass.getSimpleName}",
        e
      )
  }

  private def getUnitConversion(metric: ActivitySimMetric): Float = {
    metric match {
      case DIST | DDIST => 1f / 1609.34f
      case _            => 1f
    }
  }

  /**
    * Contains data types that is used by ActivitySim: path types, time bins and metrics
    * @param pathTypes possible path types
    * @param timeBins we don't use time bins now because data can be defined for all time bins for all path types
    *                 that Beam produces
    * @param metrics possible metrics
    */
  case class MatrixData(
    pathTypes: Set[ActivitySimPathType],
    timeBins: Set[ActivitySimTimeBin],
    metrics: Set[ActivitySimMetric]
  )

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
}
