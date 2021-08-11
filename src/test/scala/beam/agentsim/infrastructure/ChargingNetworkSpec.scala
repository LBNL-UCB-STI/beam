package beam.agentsim.infrastructure

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKitBase}
import akka.util.Timeout
import beam.agentsim.agents.BeamvilleFixtures
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleManager}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.charging.ChargingPointType.CustomChargingPoint
import beam.agentsim.infrastructure.charging.ElectricCurrentType
import beam.agentsim.infrastructure.parking.PricingModel.FlatFee
import beam.agentsim.infrastructure.parking.{ParkingType, ParkingZone}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{SimRunnerForTest, TestConfigUtils}
import com.typesafe.config.{Config, ConfigFactory}
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.scalatest.funspec.AnyFunSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.TimeUnit

class ChargingNetworkSpec
    extends AnyFunSpecLike
    with TestKitBase
    with SimRunnerForTest
    with ImplicitSender
    with Matchers
    with BeamvilleFixtures {

  lazy val config: Config = ConfigFactory
    .parseString(
      """
        akka.log-dead-letters = 10
        akka.actor.debug.fsm = true
        akka.loglevel = debug
        akka.test.timefactor = 2
        """
    )
    .withFallback(testConfig("test/input/beamville/beam.conf"))
    .resolve()

  lazy implicit val system: ActorSystem = ActorSystem("PersonAndTransitDriverSpec", config)
  override def outputDirPath: String = TestConfigUtils.testOutputDir

  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)

  val randomSeed: Int = 0

  // a coordinate in the center of the UTM coordinate system
  val coordCenterOfUTM = new Coord(500000, 5000000)
  val centerSpaceTime = SpaceTime(coordCenterOfUTM, 0)

  val geo = new GeoUtilsImpl(beamConfig)

  describe("ZonalParkingManager with only XFC charging option") {
    it(
      "should first return that an ultra fast charging stall for XFC capable vehicle, and afterward respond with a default stall for non XFC capable vehicle"
    ) {
      for {
        tazTreeMap <- ZonalParkingManagerSpec.mockTazTreeMap(
          List((coordCenterOfUTM, 10000)),
          startAtId = 1,
          167000,
          0,
          833000,
          10000000
        ) // one TAZ at agent coordinate
        config = BeamConfig(system.settings.config)
        oneParkingOption: Iterator[String] =
          """taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor,parkingZoneId
                                               |1,Workplace,FlatFee,UltraFast(250|DC),9999,5678,,0
          """.stripMargin.split("\n").toIterator
        chargingNetwork = ChargingNetworkSpec.mockChargingNetwork(
          ParkingZone.GlobalReservedFor,
          config,
          tazTreeMap,
          geo,
          oneParkingOption,
          boundingBox,
          randomSeed
        )
      } {
        val vehicleType1 = beamScenario.vehicleTypes(Id.create("BEV_XFC", classOf[BeamVehicleType]))
        val vehicle1 = new BeamVehicle(
          id = Id.createVehicleId("car-01"),
          powerTrain = new Powertrain(0.0),
          beamVehicleType = vehicleType1
        )
        val xfcChargingPoint = CustomChargingPoint("ultrafast", 250.0, ElectricCurrentType.DC)
        // first request is handled with the only stall in the system
        val firstInquiry =
          ParkingInquiry.init(centerSpaceTime, "work", beamVehicle = Some(vehicle1), triggerId = 73737)
        val expectedFirstStall =
          ParkingStall(
            Id.create(1, classOf[TAZ]),
            Id.create(1, classOf[TAZ]),
            ParkingZone.createId("0"),
            coordCenterOfUTM,
            56.78,
            Some(xfcChargingPoint),
            Some(FlatFee(56.78)),
            ParkingType.Workplace,
            ParkingZone.GlobalReservedFor
          )
        val response1 = chargingNetwork.processParkingInquiry(firstInquiry)
        assert(
          response1.get == ParkingInquiryResponse(expectedFirstStall, firstInquiry.requestId, firstInquiry.triggerId),
          "something is wildly broken"
        )

        // since only stall is in use, the second inquiry will be handled with the emergency stall
        val vehicleType2 = beamScenario.vehicleTypes(Id.create("BEV", classOf[BeamVehicleType]))
        val vehicle2 = new BeamVehicle(
          id = Id.createVehicleId("car-01"),
          powerTrain = new Powertrain(0.0),
          beamVehicleType = vehicleType2
        )
        val secondInquiry =
          ParkingInquiry.init(centerSpaceTime, "work", beamVehicle = Some(vehicle2), triggerId = 49238)
        val response2 = chargingNetwork.processParkingInquiry(secondInquiry)
        chargingNetwork.processParkingInquiry(secondInquiry)
        assert(response2.isEmpty, "it should not get an Ultra Fast charging point stall")
      }
    }
  }

}

object ChargingNetworkSpec {

  def mockChargingNetwork(
    vehicleManagerId: Id[VehicleManager],
    beamConfig: BeamConfig,
    tazTreeMap: TAZTreeMap,
    geo: GeoUtils,
    parkingDescription: Iterator[String],
    boundingBox: Envelope,
    seed: Int
  ): ChargingNetwork[TAZ] = {
    val minSearchRadius = 1000.0
    val maxSearchRadius = 16093.4 // meters, aka 10 miles
    ChargingNetwork[TAZ](
      vehicleManagerId,
      parkingDescription,
      tazTreeMap.tazQuadTree,
      tazTreeMap.idToTAZMapping,
      identity[TAZ](_),
      boundingBox,
      beamConfig,
      geo.distUTMInMeters(_, _),
      minSearchRadius,
      maxSearchRadius,
      seed
    )
  }
}
