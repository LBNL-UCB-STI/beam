package beam.router.skim.urbansim

import beam.router.skim.ActivitySimMetric._
import beam.router.skim.ActivitySimPathType._
import beam.router.skim.ActivitySimSkimmer.ExcerptData
import beam.router.skim.ActivitySimTimeBin._
import beam.router.skim.{ActivitySimMetric, ActivitySimPathType, ActivitySimTimeBin}
import beam.utils.csv.CsvWriter
import com.typesafe.scalalogging.LazyLogging
import omx.hdf5.HDF5Loader
import omx.{OmxFile, OmxMatrix}

/**
  * @author Dmitry Openkov
  */
object ActivitySimOmxWriter extends LazyLogging {

  def writeToOmx(
    filePath: String,
    skimData: Iterator[ExcerptData],
    geoUnits: Seq[String]
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

    logger.info(s"Creating new OmxFile instance for path: $filePath")
    val omxFile = new OmxFile(filePath)
    logger.info("OmxFile instance created successfully")

    logger.info(s"Shape size will be: ${geoUnits.size}x${geoUnits.size}")

    val shape: Array[Int] = Array.fill(geoUnits.size)(geoUnits.size)
    logger.info("Attempting to open new file...")
    try {
      omxFile.openNew(shape)
      logger.info("File opened successfully")
    } catch {
      case e: Exception =>
        logger.error(s"Failed to open file: ${e.getMessage}")
        logger.error(s"Exception class: ${e.getClass.getName}")
        e.printStackTrace()
    }

    val geoUnitMapping = geoUnits.zipWithIndex.toMap

    // Group the data by matrix key to process each matrix once
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

    // Process each matrix
    for {
      ((pathType, timePeriod, metrics), excerpts) <- groupedData
      metric                                      <- metrics
    } {
      val matrixName = s"${pathType}_${metric}__$timePeriod"
      val valuesFloat = Array.fill[Float](shape(0), shape(1))(Float.NaN)
      val matrix = new OmxMatrix.OmxFloatMatrix(matrixName, valuesFloat, -1.0f)

      matrix.setAttribute("mode", pathType)
      matrix.setAttribute("timePeriod", timePeriod)
      matrix.setAttribute("measure", metric.toString)

      // Fill the matrix
      for {
        excerptData <- excerpts
        row         <- geoUnitMapping.get(excerptData.originId)
        column      <- geoUnitMapping.get(excerptData.destinationId)
      } {
        matrix.getData()(row)(column) = excerptData.getValue(metric).toFloat * getUnitConversion(metric)
      }

      omxFile.addMatrix(matrix)
    }

    logger.info("Saving OMX file...")
    omxFile.save()
    logger.info("OMX file saved successfully")
    omxFile.close()
    logger.info("OMX file closed")

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
}
