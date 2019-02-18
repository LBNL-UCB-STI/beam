package beam.agentsim.agents.vehicles

import com.univocity.parsers.csv.{CsvParser, CsvParserSettings}
import java.io.{ByteArrayInputStream}

import org.scalatest.mockito.MockitoSugar
import org.mockito.Mockito._
import org.scalatest.{FunSpecLike, Matchers, _}
import scala.collection.JavaConverters._

class VehicleEnergyTest extends FunSpecLike
  with BeforeAndAfterAll
  with Matchers
  with MockitoSugar {

  describe("A VehicleEnergy") {
    it("should") {
      lazy val baseString =
        """"(1, 5]","(-8, -6]","(0, 1]","6.362255966","0.423485439","6.656215049"
          |"(1, 5],"(-6, -5]","(0, 1]","2.566056334","0.189079865","7.368500149"""".stripMargin //TODO: Mixed quotes needed
      val settings = new CsvParserSettings()
      settings.detectFormatAutomatically()
      val parser = new CsvParser(settings)
      val input = parser.iterateRecords(new ByteArrayInputStream(baseString.getBytes)).asScala
      val energy = new VehicleEnergy(input)
      val rate = energy.getRateUsing(TravelData(2,-6,1), 1)
      Math.abs(rate - 6.656215) should be < 0.001
      //val passengerSchedule: PassengerSchedule = PassengerSchedule()
      //passengerSchedule.schedule.size should be(0)
    }
  }
}
