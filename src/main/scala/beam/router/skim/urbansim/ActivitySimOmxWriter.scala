package beam.router.skim.urbansim

import beam.router.skim.ActivitySimMetric._
import beam.router.skim.ActivitySimPathType._
import beam.router.skim.ActivitySimSkimmer.ExcerptData
import beam.router.skim.ActivitySimTimeBin._
import beam.router.skim.{ActivitySimMetric, ActivitySimPathType, ActivitySimTimeBin}
import beam.utils.FileUtils
import beam.utils.csv.CsvWriter
import omx.hdf5.HDF5Loader
import omx.{OmxFile, OmxMatrix}

import scala.collection.mutable
import scala.util.Try

/**
  * @author Dmitry Openkov
  */
object ActivitySimOmxWriter {

  def writeToOmx(
    filePath: String,
    skimData: Iterator[ExcerptData],
    geoUnits: Seq[String]
  ): Try[Unit] = Try {
    HDF5Loader.prepareHdf5Library()
    val pathTypeToMatrixData: Map[ActivitySimPathType, MatrixData] = (
      for {
        data     <- activitySimMatrixData
        pathType <- data.pathTypes
        limitedData = data.copy(metrics = data.metrics & ExcerptData.supportedActivitySimMetric)
      } yield pathType -> limitedData
    ).toMap
    FileUtils.using(
      new OmxFile(filePath)
    ) { omxFile =>
      val shape: Array[Int] = Array.fill(geoUnits.size)(geoUnits.size)
      omxFile.openNew(shape)
      val geoUnitMapping = geoUnits.zipWithIndex.toMap

      val allMatrices = mutable.Map.empty[String, OmxMatrix.OmxFloatMatrix]
      for {
        excerptData <- skimData
        matrixData  <- pathTypeToMatrixData.get(excerptData.pathType).toIterable
        row         <- geoUnitMapping.get(excerptData.originId).toIterable
        column      <- geoUnitMapping.get(excerptData.destinationId).toIterable
        metric      <- matrixData.metrics
      } {
        val pathType = excerptData.pathType match {
          case rideHailMode @ (TNC_SINGLE | TNC_SHARED | TNC_SINGLE_TRANSIT | TNC_SHARED_TRANSIT) =>
            f"${rideHailMode.toString}_${excerptData.fleetName.toUpperCase}"
          case _ => excerptData.pathType.toString
        }
        val matrix = getOrCreateMatrix(allMatrices, pathType, excerptData.timePeriodString, metric, shape)
        matrix.setAttribute("mode", pathType)
        matrix.setAttribute("timePeriod", excerptData.timePeriodString)
        matrix.setAttribute("measure", metric.toString)
        matrix.getData()(row)(column) = excerptData.getValue(metric).toFloat * getUnitConversion(metric)
      }
      allMatrices.values.foreach(omxFile.addMatrix)
    // we cannot add a lookup because string arrays are not supported by hdf5lib java
    // omxFile.addLookup(new OmxStringLookup("zone_id", geoUnits.toArray, ""))
    }
    // we write geo unit mapping as a csv file next to the omx file
    CsvWriter(filePath + ".mapping", "zone_id").writeAllAndClose(geoUnits.map(Seq(_)))
  }

  private def getUnitConversion(metric: ActivitySimMetric): Float = {
    metric match {
      case DIST | DDIST => 1f / 1609.34f
      case _            => 1f
    }
  }

  private def getOrCreateMatrix(
    matrixMap: mutable.Map[String, OmxMatrix.OmxFloatMatrix],
    pathType: String,
    timeBin: String,
    metric: ActivitySimMetric,
    shape: Array[Int]
  ): OmxMatrix.OmxFloatMatrix = {
    val matrixName = s"${pathType}_${metric}__$timeBin"
    matrixMap.getOrElseUpdate(
      matrixName, {
        val valuesFloat = Array.fill[Float](shape(0), shape(1))(Float.NaN)
        new OmxMatrix.OmxFloatMatrix(matrixName, valuesFloat, -1.0f)
      }
    )
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
    ),
    MatrixData(
      Set(TNC_SINGLE_TRANSIT, TNC_SHARED_TRANSIT),
      ActivitySimTimeBin.values.toSet,
      Set(TOTIVT, FAR, XWAIT, KEYIVT, IWAIT, DTIM, BOARDS, DDIST, WAUX, TRIPS, FAILURES)
    )
  )
}
