package beam.router.skim.urbansim

import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.router.skim.ActivitySimPathType.{DRV_COM_WLK, DRV_LRF_WLK}
import beam.router.skim.ActivitySimSkimmer.ExcerptData
import omx.OmxFile
import omx.OmxMatrix.OmxDoubleMatrix
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
        ExcerptData("MD", DRV_LRF_WLK, "100574", "10069A", 100, 90, 80, 70, 60, 50, 40, 30, 20, 10, 5, 4, 3, 2, 1, 1, 0)
      )
      val path = "output/test/activitysim_skims.omx"
      ActivitySimOmxWriter.writeToOmx(path, excerptData.iterator, geoUnits)
      val omxFile = new OmxFile(path)
      omxFile.openReadOnly()
      val matrixNames = omxFile.getMatrixNames.asScala.toList
      matrixNames should contain theSameElementsAs List(
        "DRV_LRF_WLK_FERRYIVT__MD",
        "DRV_COM_WLK_DDIST__PM",
        "DRV_LRF_WLK_WAUX__MD",
        "DRV_COM_WLK_DDIST__AM",
        "DRV_COM_WLK_FAR__AM",
        "DRV_COM_WLK_KEYIVT__PM",
        "DRV_LRF_WLK_TOTIVT__MD",
        "DRV_LRF_WLK_BOARDS__MD",
        "DRV_LRF_WLK_FAR__MD",
        "DRV_COM_WLK_KEYIVT__AM",
        "DRV_COM_WLK_BOARDS__AM",
        "DRV_LRF_WLK_DDIST__MD",
        "DRV_COM_WLK_WAUX__PM",
        "DRV_LRF_WLK_KEYIVT__MD",
        "DRV_LRF_WLK_DTIM__MD",
        "DRV_COM_WLK_TOTIVT__PM",
        "DRV_COM_WLK_BOARDS__PM",
        "DRV_COM_WLK_DTIM__PM",
        "DRV_COM_WLK_DTIM__AM",
        "DRV_COM_WLK_TOTIVT__AM",
        "DRV_COM_WLK_WAUX__AM",
        "DRV_COM_WLK_FAR__PM",
        "DRV_LRF_WLK_TRIPS__MD",
        "DRV_LRF_WLK_FAILURES__MD",
        "DRV_COM_WLK_TRIPS__AM",
        "DRV_COM_WLK_FAILURES__AM",
        "DRV_COM_WLK_TRIPS__PM",
        "DRV_COM_WLK_FAILURES__PM",
        "DRV_LRF_WLK_XWAIT__MD",
        "DRV_COM_WLK_XWAIT__AM",
        "DRV_COM_WLK_IWAIT__PM",
        "DRV_COM_WLK_XWAIT__PM",
        "DRV_COM_WLK_IWAIT__AM",
        "DRV_LRF_WLK_IWAIT__MD"
      )
      //total in vehicle time data for path type DRV_LRF_WLK and time bin MD
      val matrix = omxFile.getMatrix("DRV_LRF_WLK_TOTIVT__MD").asInstanceOf[OmxDoubleMatrix]
      matrix.getShape()(0) shouldBe 864
      matrix.getShape()(1) shouldBe 864
      val mapping = geoUnits.zipWithIndex.toMap
      val originRow = mapping("100574")
      val destinationColum = mapping("10069A")
      matrix.getData()(originRow)(destinationColum) shouldBe 90.0
    }
  }
}
