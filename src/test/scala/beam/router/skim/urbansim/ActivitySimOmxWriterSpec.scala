package beam.router.skim.urbansim

import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.router.skim.ActivitySimPathType.{DRV_COM_WLK, DRV_LOC_WLK, TNC_SINGLE, WLK_LOC_WLK, WLK_LRF_WLK}
import beam.router.skim.ActivitySimSkimmer.ExcerptData
import omx.OmxFile
import omx.OmxMatrix.OmxFloatMatrix
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.immutable.SortedSet
import scala.jdk.CollectionConverters.asScalaSetConverter

/**
  * @author Dmitry Openkov
  */
class ActivitySimOmxWriterSpec extends AnyWordSpecLike with Matchers {
  "ActivitySimOmxWriter" should {

    "write all activitysim skims to an omx file" in {
      val tazMap = TAZTreeMap.getTazTreeMap("test/input/sf-light/taz-centers.csv")
      val geoUnits = SortedSet[String](tazMap.getTAZs.map(_.tazId.toString).toSeq: _*)
      val excerptData = IndexedSeq(
        ExcerptData(
          "AM",
          DRV_COM_WLK,
          "",
          "100827",
          "100413",
          100,
          90,
          80,
          70,
          60,
          50,
          40,
          30,
          20,
          10,
          5,
          4,
          3,
          2,
          1,
          1,
          0
        ),
        ExcerptData(
          "AM",
          DRV_COM_WLK,
          "None",
          "100413",
          "100827",
          120,
          190,
          180,
          170,
          160,
          150,
          140,
          130,
          120,
          110,
          50,
          40,
          1,
          2,
          1,
          1,
          0
        ),
        ExcerptData(
          "PM",
          DRV_COM_WLK,
          "",
          "100627",
          "100413",
          100,
          90,
          80,
          70,
          60,
          50,
          40,
          30,
          20,
          10,
          5,
          4,
          3,
          2,
          1,
          1,
          0
        ),
        ExcerptData(
          "MD",
          WLK_LOC_WLK,
          "",
          "100574",
          "10069A",
          100,
          90,
          80,
          70,
          60,
          50,
          40,
          30,
          20,
          10,
          5,
          4,
          3,
          2,
          1,
          1,
          0
        ),
        ExcerptData(
          "AM",
          TNC_SINGLE,
          "Cruise",
          "100827",
          "100413",
          100,
          90,
          80,
          70,
          60,
          50,
          40,
          30,
          20,
          10,
          5,
          4,
          3,
          2,
          1,
          1,
          0
        ),
        ExcerptData(
          "AM",
          TNC_SINGLE,
          "GlobalRHM",
          "100827",
          "100413",
          100,
          90,
          80,
          70,
          60,
          50,
          40,
          30,
          20,
          10,
          5,
          4,
          3,
          2,
          1,
          1,
          0
        )
      )
      val path = "output/test/activitysim_skims.omx"
      ActivitySimOmxWriter.writeToOmx(path, excerptData.iterator, geoUnits)
      val omxFile = new OmxFile(path)
      omxFile.openReadOnly()
      val matrixNames = omxFile.getMatrixNames.asScala.toList
      matrixNames should contain theSameElementsAs List(
        "DRV_COM_WLK_DDIST__PM",
        "WLK_LOC_WLK_WAUX__MD",
        "DRV_COM_WLK_DDIST__AM",
        "DRV_COM_WLK_FAR__AM",
        "DRV_COM_WLK_KEYIVT__PM",
        "WLK_LOC_WLK_TOTIVT__MD",
        "WLK_LOC_WLK_BOARDS__MD",
        "WLK_LOC_WLK_FAR__MD",
        "DRV_COM_WLK_KEYIVT__AM",
        "DRV_COM_WLK_BOARDS__AM",
        "DRV_COM_WLK_WAUX__PM",
        "DRV_COM_WLK_TOTIVT__PM",
        "DRV_COM_WLK_BOARDS__PM",
        "DRV_COM_WLK_DTIM__PM",
        "DRV_COM_WLK_DTIM__AM",
        "DRV_COM_WLK_TOTIVT__AM",
        "DRV_COM_WLK_WAUX__AM",
        "DRV_COM_WLK_FAR__PM",
        "WLK_LOC_WLK_TRIPS__MD",
        "WLK_LOC_WLK_FAILURES__MD",
        "DRV_COM_WLK_TRIPS__AM",
        "DRV_COM_WLK_FAILURES__AM",
        "DRV_COM_WLK_TRIPS__PM",
        "DRV_COM_WLK_FAILURES__PM",
        "WLK_LOC_WLK_XWAIT__MD",
        "DRV_COM_WLK_XWAIT__AM",
        "DRV_COM_WLK_IWAIT__PM",
        "DRV_COM_WLK_XWAIT__PM",
        "DRV_COM_WLK_IWAIT__AM",
        "WLK_LOC_WLK_IWAIT__MD",
        "TNC_SINGLE_CRUISE_IWAIT__MD",
        "TNC_SINGLE_CRUISE_TOTIVT__MD",
        "TNC_SINGLE_CRUISE_DDIST__MD",
        "TNC_SINGLE_CRUISE_FAR__MD",
        "TNC_SINGLE_GLOBALRHM_IWAIT__MD",
        "TNC_SINGLE_GLOBALRHM_TOTIVT__MD",
        "TNC_SINGLE_GLOBALRHM_DDIST__MD",
        "TNC_SINGLE_GLOBALRHM_FAR__MD"
//        "WLK_TRN_WLK_IVT__MD",
//        "WLK_TRN_WLK_XWAIT__MD",
//        "WLK_TRN_WLK_IWAIT__MD",
//        "WLK_TRN_WLK_WAUX__MD",
//        "WLK_TRN_WLK_WACC__MD",
//        "WLK_TRN_WLK_WEGR__MD",
//        "WLK_TRN_WLK_TRIPS__MD",
//        "WLK_TRN_WLK_FAILURES__MD"
      )
      //total in vehicle time data for path type DRV_LOC_WLK and time bin MD
      val matrix = omxFile.getMatrix("WLK_LOC_WLK_TOTIVT__MD").asInstanceOf[OmxFloatMatrix]
      matrix.getShape()(0) shouldBe 864
      matrix.getShape()(1) shouldBe 864
      val mapping = geoUnits.zipWithIndex.toMap
      val originRow = mapping("100574")
      val destinationColum = mapping("10069A")
      matrix.getData()(originRow)(destinationColum) shouldBe 90.0
    }
  }
}
