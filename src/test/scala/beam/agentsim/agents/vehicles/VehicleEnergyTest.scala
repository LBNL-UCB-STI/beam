/*package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.BeamVehicle.FuelConsumptionData
import com.univocity.parsers.csv.CsvParser
import java.io.ByteArrayInputStream

import beam.agentsim.agents.vehicles.ConsumptionRateFilterStore.Primary
import org.scalatest.mockito.MockitoSugar
import org.scalatest._

import scala.collection.JavaConverters._
import scala.concurrent.Future

class VehicleEnergyTest extends FunSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  val floatAllowedDiscrepancy = 0.001
  val linkId = 1
  val vehicleEnergyHeader = "speed_mph_float_bins,grade_percent_float_bins,num_lanes_int_bins,miles,gallons,rate"
  val linkIdHeader = "id,from,to,freespeed,length,travelTime,from_elevation,to_elevation,delta_elevation,average_gradient"
  val linkIdToGradePercentString =
    s"""$linkIdHeader
      |1,1,0,4.166666667,75.065,18.0156,88.96134949,84.50296021,-4.458389282,-6""".stripMargin
  val linkIdToGradePercentRecordsRetriever = createRecordsIterableRetrieverFrom(linkIdToGradePercentString)

  describe("A VehicleEnergy with the same speed and number of lanes") {
    it("should return the rate that fits the grade percent") {
      lazy val vehicleEnergyString =
        s"""$vehicleEnergyHeader
          |"(1, 5]","(-8, -6]","(0, 1]",6.362255966266373,0.42348543910738917,6.656215049390844
          |"(1, 5]","(-6, -5]","(0, 1]",2.566056334197673,0.18907986480158595,7.368500148719666""".stripMargin
      val recordsRetriever = createRecordsIterableRetrieverFrom(vehicleEnergyString)
      val consumptionRateFilterStore = new ConsumptionRateFilterStore {
        override def getPrimaryConsumptionRateFilterFor(vehicleType: BeamVehicleType):
          Option[Future[ConsumptionRateFilter]] = ???

        override def getSecondaryConsumptionRateFilterFor(vehicleType: BeamVehicleType):
          Option[Future[ConsumptionRateFilter]] = ???
      }
      val energy = new VehicleEnergy(consumptionRateFilterStore, linkIdToGradePercentRecordsRetriever)
      val consumption = energy.getFuelConsumptionEnergyInJoulesUsing(
        createFuelConsumptionDataUsing(2, -6, 1), _ => 1, Primary)
      val rate = convertRateFromJoulesPerMeter(energy, consumption)
      Math.abs(rate - 6.656215049390844) should be < floatAllowedDiscrepancy
    }
  }

  describe("A VehicleEnergy with the same speed and grade percent") {
    it("should return the rate that fits the number of lanes") {
      lazy val vehicleEnergyString =
        s"""$vehicleEnergyHeader
          |"(1, 5]","(-8, -6]","(3, 4]",30.17397314735461,2.423751926047644,8.032591247467646
          |"(1, 5]","(-8, -6]","(4, 10]",44.557817527020944,3.683582791215134,8.266973105182542""".stripMargin
      val recordsRetriever = createRecordsIterableRetrieverFrom(vehicleEnergyString)
      val energy = new VehicleEnergy(recordsRetriever, linkIdToGradePercentRecordsRetriever)
      val consumption = energy.getFuelConsumptionEnergyInJoulesUsing(createFuelConsumptionDataUsing(2, -6, 4), _ => 1)
      val rate = convertRateFromJoulesPerMeter(energy, consumption)
      Math.abs(rate - 8.032591247467646) should be < floatAllowedDiscrepancy
    }
  }

  describe("A VehicleEnergy with the same grade percent and number of lanes") {
    it("should return the rate that fits the speed") {
      lazy val vehicleEnergyString =
        s"""$vehicleEnergyHeader
          |"(1, 5]","(-8, -6]","(3, 4]",30.17397314735461,2.423751926047644,8.032591247467646
          |"(5, 10]","(-8, -6]","(3, 4]",44.557817527020944,3.683582791215134,8.266973105182542""".stripMargin
      val recordsRetriever = createRecordsIterableRetrieverFrom(vehicleEnergyString)
      val energy = new VehicleEnergy(recordsRetriever, linkIdToGradePercentRecordsRetriever)
      val consumption = energy.getFuelConsumptionEnergyInJoulesUsing(createFuelConsumptionDataUsing(2, -6, 4), _ => 1)
      val rate = convertRateFromJoulesPerMeter(energy, consumption)
      Math.abs(rate - 8.032591247467646) should be < floatAllowedDiscrepancy
    }
  }

  describe("A VehicleEnergy that doesn't match anything") {
    it("should return the fallback consumption") {
      lazy val vehicleEnergyString =
        s"""$vehicleEnergyHeader
          |"(1, 5]","(-8, -6]","(3, 4]",30.17397314735461,2.423751926047644,8.032591247467646
          |"(5, 10]","(-8, -6]","(3, 4]",44.557817527020944,3.683582791215134,8.266973105182542""".stripMargin
      val recordsRetriever = createRecordsIterableRetrieverFrom(vehicleEnergyString)
      val energy = new VehicleEnergy(recordsRetriever, linkIdToGradePercentRecordsRetriever)
      val consumption =
        energy.getFuelConsumptionEnergyInJoulesUsing(createFuelConsumptionDataUsing(12, -6, 4), _ => 1.05)
      Math.abs(consumption - 1.05) should be < floatAllowedDiscrepancy
    }
  }

  private def createRecordsIterableRetrieverFrom(vehicleString: String) = { (csvParser: CsvParser) =>
    csvParser.iterateRecords(new ByteArrayInputStream(vehicleString.getBytes)).asScala
  }

  private def convertRateFromJoulesPerMeter(vehicleEnergy: VehicleEnergy, consumption: Double) =
    consumption / vehicleEnergy.conversionRateForJoulesPerMeterConversionFromGallonsPer100Miles

  private def createFuelConsumptionDataUsing(speed: Int, gradePercent: Int, numberOfLanes: Int) = {
    IndexedSeq(
      FuelConsumptionData(
        linkId,
        BeamVehicleType.defaultCarBeamVehicleType,
        Option(numberOfLanes),
        linkLength = Option(1),
        averageSpeed = None,
        freeFlowSpeed = Option(speed)
      )
    )
  }
}
 */
