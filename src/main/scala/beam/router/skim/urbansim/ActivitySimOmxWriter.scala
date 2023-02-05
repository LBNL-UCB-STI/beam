package beam.router.skim.urbansim

import beam.router.skim.ActivitySimMetric._
import beam.router.skim.ActivitySimPathType._
import beam.router.skim.ActivitySimSkimmer.ExcerptData
import beam.router.skim.ActivitySimTimeBin._
import beam.router.skim.{ActivitySimMetric, ActivitySimPathType, ActivitySimTimeBin}
import beam.utils.FileUtils
import omx.hdf5.HDF5Loader
import omx.{OmxFile, OmxMatrix}

import scala.collection.{mutable, SortedSet}
import scala.util.Try

/**
  * @author Dmitry Openkov
  */
object ActivitySimOmxWriter {

  def writeToOmx(
    filePath: String,
    skimData: Iterator[ExcerptData],
    geoUnits: SortedSet[String]
  ): Try[Unit] = Try {
    HDF5Loader.prepareHdf5Library()
    FileUtils.using(
      new OmxFile(filePath)
    ) { omxFile =>
      val shape: Array[Int] = Array.fill(geoUnits.size)(geoUnits.size)
      omxFile.openNew(shape)
      val geoUnitMapping = geoUnits.zipWithIndex.toMap

      val pathTypeToMatrixData: Map[ActivitySimPathType, MatrixData] = (
        for {
          data     <- activitySimMatrixData
          pathType <- data.pathTypes
          limitedData = data.copy(metrics = data.metrics & ExcerptData.supportedActivitySimMetric)
        } yield pathType -> limitedData
      ).toMap

      val allMatrices = mutable.Map.empty[String, OmxMatrix.OmxDoubleMatrix]
      for {
        excerptData <- skimData
        matrixData  <- pathTypeToMatrixData.get(excerptData.pathType).toIterable
        row         <- geoUnitMapping.get(excerptData.originId).toIterable
        column      <- geoUnitMapping.get(excerptData.destinationId).toIterable
        metric      <- matrixData.metrics
      } {
        val matrix = getOrCreateMatrix(allMatrices, excerptData.pathType, excerptData.timePeriodString, metric, shape)
        matrix.getData()(row)(column) = excerptData.getValue(metric)
      }
      allMatrices.values.foreach(omxFile.addMatrix)
    }

  }

  private def getOrCreateMatrix(
    matrixMap: mutable.Map[String, OmxMatrix.OmxDoubleMatrix],
    pathType: ActivitySimPathType,
    timeBin: String,
    metric: ActivitySimMetric,
    shape: Array[Int]
  ): OmxMatrix.OmxDoubleMatrix = {
    val matrixName = s"${pathType}_${timeBin}__$metric"
    matrixMap.getOrElseUpdate(
      matrixName, {
        val valuesDouble = Array.fill[Double](shape(0), shape(1))(Double.NaN)
        new OmxMatrix.OmxDoubleMatrix(matrixName, valuesDouble, -1.0)
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
      Set(TOTIVT, FAR, WAIT, XWAIT, KEYIVT, IWAIT, DTIM, BOARDS, DDIST, WAUX)
    ),
    MatrixData(
      Set(DRV_LOC_WLK, WLK_LOC_DRV),
      ActivitySimTimeBin.values.toSet,
      Set(TOTIVT, FAR, WAIT, XWAIT, IWAIT, DTIM, BOARDS, DDIST, WAUX)
    ),
    MatrixData(
      Set(DRV_LRF_WLK, WLK_LRF_DRV),
      ActivitySimTimeBin.values.toSet,
      Set(TOTIVT, FERRYIVT, FAR, WAIT, XWAIT, KEYIVT, DTIM, IWAIT, BOARDS, DDIST, WAUX)
    ),
    MatrixData(
      Set(HOV2TOLL, HOV3TOLL, SOVTOLL),
      ActivitySimTimeBin.values.toSet,
      Set(BTOLL, VTOLL, TIME, DIST)
    ),
    MatrixData(Set(HOV2, HOV3, SOV), ActivitySimTimeBin.values.toSet, Set(BTOLL, TIME, DIST)),
    MatrixData(
      Set(WLK_COM_WLK, WLK_EXP_WLK, WLK_HVY_WLK),
      ActivitySimTimeBin.values.toSet,
      Set(TOTIVT, FAR, WAIT, XWAIT, KEYIVT, IWAIT, BOARDS, WAUX)
    ),
    MatrixData(
      Set(WLK_LOC_WLK),
      ActivitySimTimeBin.values.toSet,
      Set(TOTIVT, WAIT, FAR, XWAIT, IWAIT, BOARDS, WAUX)
    ),
    MatrixData(
      Set(WLK_LRF_WLK),
      ActivitySimTimeBin.values.toSet,
      Set(TOTIVT, FERRYIVT, FAR, WAIT, XWAIT, KEYIVT, IWAIT, BOARDS, WAUX)
    ),
    MatrixData(
      Set(WLK_TRN_WLK),
      Set(PM_PEAK, MIDDAY, AM_PEAK),
      Set(WACC, IVT, XWAIT, IWAIT, WEGR, WAUX)
    )
  )
}
