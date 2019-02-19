package beam.agentsim.agents.vehicles

import com.univocity.parsers.csv.CsvParser
import java.io.ByteArrayInputStream
import org.scalatest.mockito.MockitoSugar
import org.scalatest._
import scala.collection.JavaConverters._

class VehicleEnergyTest extends FunSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  val floatAllowedDiscrepancy = 0.001

  describe("A VehicleEnergy with the same speed and number of lanes") {
    it("should return the rate that fits the grade percent") {
      lazy val vehicleEnergyString =
        """"(1, 5]","(-8, -6]","(0, 1]",6.362255966266373,0.42348543910738917,6.656215049390844
          |"(1, 5]","(-6, -5]","(0, 1]",2.566056334197673,0.18907986480158595,7.368500148719666""".stripMargin
      val recordsRetriever = createRecordsIterableRetrieverFrom(vehicleEnergyString)
      val energy = new VehicleEnergy(recordsRetriever)
      val rate = energy.getRateUsing(TravelData(2, -6, 1), 1)
      Math.abs(rate - 6.656215049390844) should be < floatAllowedDiscrepancy
    }
  }

  describe("A VehicleEnergy with the same speed and grade percent") {
    it("should return the rate that fits the number of lanes") {
      lazy val vehicleEnergyString =
        """"(1, 5]","(-8, -6]","(3, 4]",30.17397314735461,2.423751926047644,8.032591247467646
          |"(1, 5]","(-8, -6]","(4, 10]",44.557817527020944,3.683582791215134,8.266973105182542""".stripMargin
      val recordsRetriever = createRecordsIterableRetrieverFrom(vehicleEnergyString)
      val energy = new VehicleEnergy(recordsRetriever)
      val rate = energy.getRateUsing(TravelData(2, -6, 4), 1)
      Math.abs(rate - 8.032591247467646) should be < floatAllowedDiscrepancy
    }
  }

  describe("A VehicleEnergy with the same grade percent and number of lanes") {
    it("should return the rate that fits the speed") {
      lazy val vehicleEnergyString =
        """"(1, 5]","(-8, -6]","(3, 4]",30.17397314735461,2.423751926047644,8.032591247467646
          |"(5, 10]","(-8, -6]","(3, 4]",44.557817527020944,3.683582791215134,8.266973105182542""".stripMargin
      val recordsRetriever = createRecordsIterableRetrieverFrom(vehicleEnergyString)
      val energy = new VehicleEnergy(recordsRetriever)
      val rate = energy.getRateUsing(TravelData(2, -6, 4), 1)
      Math.abs(rate - 8.032591247467646) should be < floatAllowedDiscrepancy
    }
  }

  describe("A VehicleEnergy that doesn't match anything") {
    it("should return the fallback rate") {
      lazy val vehicleEnergyString =
        """"(1, 5]","(-8, -6]","(3, 4]",30.17397314735461,2.423751926047644,8.032591247467646
          |"(5, 10]","(-8, -6]","(3, 4]",44.557817527020944,3.683582791215134,8.266973105182542""".stripMargin
      val recordsRetriever = createRecordsIterableRetrieverFrom(vehicleEnergyString)
      val energy = new VehicleEnergy(recordsRetriever)
      val rate = energy.getRateUsing(TravelData(12, -6, 4), 1.05f)
      Math.abs(rate - 1.05) should be < floatAllowedDiscrepancy
    }
  }

  private def createRecordsIterableRetrieverFrom(vehicleEnergyString: String) = { (csvParser: CsvParser) =>
    csvParser.iterateRecords(new ByteArrayInputStream(vehicleEnergyString.getBytes)).asScala
  }
}
