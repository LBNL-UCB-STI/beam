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
        ExcerptData("AM", DRV_COM_WLK, "100827", "100413", 100, 90, 80, 70, 60, 50, 40, 30, 20, 10, 5, 4, 3),
        ExcerptData("AM", DRV_COM_WLK, "100413", "100827", 120, 190, 180, 170, 160, 150, 140, 130, 120, 110, 50, 40, 1),
        ExcerptData("PM", DRV_COM_WLK, "100627", "100413", 100, 90, 80, 70, 60, 50, 40, 30, 20, 10, 5, 4, 3),
        ExcerptData("MD", DRV_LRF_WLK, "100574", "10069A", 100, 90, 80, 70, 60, 50, 40, 30, 20, 10, 5, 4, 3)
      )
      val path = "output/test/activitysim_skims.omx"
      ActivitySimOmxWriter.writeToOmx(path, excerptData.iterator, geoUnits)
      val omxFile = new OmxFile(path)
      omxFile.openReadOnly()
      val matrixNames = omxFile.getMatrixNames.asScala.toList
      matrixNames should contain theSameElementsAs List(
        "DRV_LRF_WLK_MD__FERRYIVT",
        "DRV_COM_WLK_PM__DDIST",
        "DRV_LRF_WLK_MD__WAUX",
        "DRV_COM_WLK_AM__DDIST",
        "DRV_COM_WLK_AM__FAR",
        "DRV_COM_WLK_PM__KEYIVT",
        "DRV_LRF_WLK_MD__TOTIVT",
        "DRV_LRF_WLK_MD__BOARDS",
        "DRV_LRF_WLK_MD__FAR",
        "DRV_COM_WLK_AM__KEYIVT",
        "DRV_COM_WLK_AM__BOARDS",
        "DRV_LRF_WLK_MD__DDIST",
        "DRV_COM_WLK_PM__WAUX",
        "DRV_LRF_WLK_MD__KEYIVT",
        "DRV_LRF_WLK_MD__DTIM",
        "DRV_COM_WLK_PM__TOTIVT",
        "DRV_COM_WLK_PM__BOARDS",
        "DRV_COM_WLK_PM__DTIM",
        "DRV_COM_WLK_AM__DTIM",
        "DRV_COM_WLK_AM__TOTIVT",
        "DRV_COM_WLK_AM__WAUX",
        "DRV_COM_WLK_PM__FAR"
      )
      //total in vehicle time data for path type DRV_LRF_WLK and time bin MD
      val matrix = omxFile.getMatrix("DRV_LRF_WLK_MD__TOTIVT").asInstanceOf[OmxDoubleMatrix]
      matrix.getShape()(0) shouldBe 864
      matrix.getShape()(1) shouldBe 864
      val mapping = geoUnits.zipWithIndex.toMap
      val originRow = mapping("100574")
      val destinationColum = mapping("10069A")
      matrix.getData()(originRow)(destinationColum) shouldBe 90.0
    }
  }
}
