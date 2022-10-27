package beam.physsim.analysis

import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleCategory}
import beam.router.LinkTravelTimeContainer
import beam.utils.VolumesAnalyzerFixed
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.api.core.v01.population.Person
import org.matsim.core.config.ConfigUtils
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.core.network.io.MatsimNetworkReader
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.core.trafficmonitoring.TravelTimeCalculator
import org.matsim.vehicles.Vehicle
import org.mockito.Mockito.{mock, when}
import org.scalatest.OptionValues._
import org.scalatest.TryValues._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.JavaConverters._

/**
  * @author Dmitry Openkov
  */
class LinkStatsWithVehicleCategorySpec extends AnyWordSpecLike with Matchers {
  private val EVENTS_FILE_PATH = "test/input/equil-square/test-data/physSimEvents-relative-speeds.xml"
  private val NETWORK_FILE_PATH = "test/input/equil-square/test-data/physSimNetwork-relative-speeds.xml"
  private val LINKSTATS_CSV_PATH = "output/test/linkstats-by-category.csv"

  val (
    network: Network,
    ttConfigGroup: TravelTimeCalculatorConfigGroup,
    travelTimeCalculator: TravelTimeCalculator,
    volumeAnalyzer: VolumesAnalyzerFixed
  ) = readPhysSimEvents

  "LinkStatsWithVehicleCategory" when {
    val linkStats = new LinkStatsWithVehicleCategory(network, ttConfigGroup)
    val (totalLinkData, linkData, nofHours) = linkStats.calculateLinkData(
      volumeAnalyzer,
      travelTimeCalculator.getLinkTravelTimes,
      IndexedSeq("MediumDutyPassenger", "Car")
    )
    "calculates volume" should {
      "return correct volume for each vehicle category" in {
        nofHours shouldBe 68
        withClue(s"According to event file $EVENTS_FILE_PATH, bused 1,6 went through link 248 2 times:") {
          val linkDataOption = linkData("MediumDutyPassenger").get(Id.createLinkId(248))
          linkDataOption.value.getSumVolume(9) shouldBe 0.0
          linkDataOption.value.getSumVolume(10) shouldBe 1.0
          linkDataOption.value.getSumVolume(11) shouldBe 1.0
          linkDataOption.value.getSumVolume(12) shouldBe 0.0
        }
        withClue(s"According to event file $EVENTS_FILE_PATH, cars 1-9 went through link 248 9 times:") {
          val linkDataOption = linkData("Car").get(Id.createLinkId(248))
          linkDataOption.value.getSumVolume(5) shouldBe 0.0
          linkDataOption.value.getSumVolume(6) shouldBe 0.0
          linkDataOption.value.getSumVolume(7) shouldBe 1.0
          linkDataOption.value.getSumVolume(20) shouldBe 1.0
          linkDataOption.value.getSumVolume(21) shouldBe 2.0
          linkDataOption.value.getSumVolume(23) shouldBe 1.0
          linkDataOption.value.getSumVolume(33) shouldBe 1.0
          linkDataOption.value.getSumVolume(40) shouldBe 1.0
          linkDataOption.value.getSumVolume(51) shouldBe 1.0
          linkDataOption.value.getSumVolume(53) shouldBe 1.0
          linkDataOption.value.getSumVolume(54) shouldBe 0.0
        }
      }
      "return correct total volume" in {
        withClue(s"According to event file $EVENTS_FILE_PATH, vehicles went through link 112 12 times:") {
          val linkDataOption = totalLinkData.get(Id.createLinkId(112))
          linkDataOption.value.getSumVolume(5) shouldBe 0.0
          linkDataOption.value.getSumVolume(6) shouldBe 8.0
          linkDataOption.value.getSumVolume(7) shouldBe 4.0
          linkDataOption.value.getSumVolume(8) shouldBe 0.0
        }
      }
    }
    "saves linkstats to csv" should {
      "save it that WarmStart could read it" in {
        val result = linkStats.writeLinkStatsWithTruckVolumes(
          volumeAnalyzer,
          travelTimeCalculator.getLinkTravelTimes,
          LINKSTATS_CSV_PATH
        )
        val (_, _, nofHours) = result.success.value
        nofHours shouldBe 68
        val container = new LinkTravelTimeContainer(LINKSTATS_CSV_PATH, 3600, nofHours)
        val link = mock(classOf[Link])
        when(link.getId).thenReturn(Id.createLinkId(4))
        val person = mock(classOf[Person])
        val vehicle = mock(classOf[Vehicle])
        val travelTime17 = container.getLinkTravelTime(link, 17 * 3600, person, vehicle)
        travelTime17 shouldBe 0.4 +- 0.01
        val travelTime18 = container.getLinkTravelTime(link, 18 * 3600, person, vehicle)
        travelTime18 shouldBe 44478.89 +- 0.01
      }
    }
  }

  private def readPhysSimEvents
    : (Network, TravelTimeCalculatorConfigGroup, TravelTimeCalculator, VolumesAnalyzerFixed) = {
    val config = ConfigUtils.createConfig
    config.travelTimeCalculator().setMaxTime(245162)
    val scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig)
    val network = scenario.getNetwork
    val matsimNetworkReader = new MatsimNetworkReader(network)
    matsimNetworkReader.readFile(NETWORK_FILE_PATH)

    val ttConfigGroup: TravelTimeCalculatorConfigGroup = config.travelTimeCalculator
    val travelTimeCalculator = new TravelTimeCalculator(network, ttConfigGroup)
    val eventsManager = EventsUtils.createEventsManager
    eventsManager.addHandler(travelTimeCalculator)
    val volumeAnalyzer =
      new VolumesAnalyzerFixed(3600, ttConfigGroup.getMaxTime - 1, network, createVehicleMap().asJava)
    eventsManager.addHandler(volumeAnalyzer)

    val matsimEventsReader = new MatsimEventsReader(eventsManager)
    matsimEventsReader.readFile(EVENTS_FILE_PATH)
    (network, ttConfigGroup, travelTimeCalculator, volumeAnalyzer)
  }

  def createVehicleMap(): Map[Id[BeamVehicle], BeamVehicle] = {
    Map(
      createVehicleWithId("bus:B2-EAST-1", VehicleCategory.MediumDutyPassenger),
      createVehicleWithId("bus:B2-EAST-6", VehicleCategory.MediumDutyPassenger)
    ) ++ (1 to 9).map(i => createVehicleWithId(i.toString, VehicleCategory.Car))
  }

  private def createVehicleWithId(
    vehicleIdStr: String,
    vehicleCategory: VehicleCategory
  ): (Id[BeamVehicle], BeamVehicle) = {
    val vehicleId = vehicleIdStr.createId[BeamVehicle]
    val vehicleType = mock(classOf[BeamVehicleType])
    when(vehicleType.vehicleCategory).thenReturn(vehicleCategory)
    val vehicle = mock(classOf[BeamVehicle])
    when(vehicle.beamVehicleType).thenReturn(vehicleType)
    (vehicleId, vehicle)
  }
}
