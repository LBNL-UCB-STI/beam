package beam.agentsim.infrastructure

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKitBase}
import akka.util.Timeout
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.BeamvilleFixtures
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleCategory, VehicleManager}
import beam.agentsim.infrastructure.charging.ChargingPointType.CustomChargingPoint
import beam.agentsim.infrastructure.charging.ElectricCurrentType
import beam.agentsim.infrastructure.parking.PricingModel.{Block, FlatFee}
import beam.agentsim.infrastructure.parking.{ParkingType, ParkingZone, PricingModel}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{SimRunnerForTest, TestConfigUtils}
import com.typesafe.config.{Config, ConfigFactory}
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.scalatest.{FunSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.io.Source
import scala.util.Random

class ZonalParkingManagerSpec
    extends FunSpecLike
    with TestKitBase
    with SimRunnerForTest
    with MockitoSugar
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

  val randomSeed: Long = 0

  // a coordinate in the center of the UTM coordinate system
  val coordCenterOfUTM = new Coord(500000, 5000000)

  val geo = new GeoUtilsImpl(beamConfig)

  private val managers = Map[Id[VehicleManager], VehicleManager](
    VehicleManager.privateVehicleManager.managerId -> VehicleManager.privateVehicleManager
  )

  describe("ZonalParkingManager with no parking") {
    it("should return a response with an emergency stall") {

      for {
        tazTreeMap <- ZonalParkingManagerSpec.mockTazTreeMap(
          coords = List((coordCenterOfUTM, 10000)),
          startAtId = 1,
          xMin = 167000,
          yMin = 0,
          xMax = 833000,
          yMax = 10000000
        ) // one TAZ at agent coordinate
        config = beamConfig
        emptyParkingDescription: Iterator[String] = Iterator.empty
        zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(
          tazTreeMap,
          geo,
          emptyParkingDescription,
          boundingBox,
          new Random(randomSeed),
          managers,
          config
        )
      } {
        val inquiry = ParkingInquiry(coordCenterOfUTM, "work")
        val expectedStall: ParkingStall = ParkingStall.lastResortStall(
          new Envelope(
            inquiry.destinationUtm.getX + 2000,
            inquiry.destinationUtm.getX - 2000,
            inquiry.destinationUtm.getY + 2000,
            inquiry.destinationUtm.getY - 2000
          ),
          new Random(randomSeed),
          geoId = TAZ.EmergencyTAZId,
        )

        val response = zonalParkingManager.processParkingInquiry(inquiry)

        // note on the random seed:
        // since there are no TAZs to search and sample parking locations from,
        // the random number generator is unused by the [[ZonalParkingManager]] search, and we can
        // therefore rely on the coordinate that is generated when [[ZonalParkingManager]] calls [[ParkingStall.emergencyStall]] internally
        assert(response.isDefined, "no response")
        assert(response.get == ParkingInquiryResponse(expectedStall, inquiry.requestId), "something is wildly broken")
      }
    }
  }

  describe("ZonalParkingManager with one parking option") {
    it("should first return that only stall, and afterward respond with the default stall") {

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
        oneParkingOption: Iterator[String] = """taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents
            |1,Workplace,FlatFee,None,1,1234,
            |
          """.stripMargin.split("\n").toIterator
        zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(
          tazTreeMap,
          geo,
          oneParkingOption,
          boundingBox,
          new Random(randomSeed),
          managers,
          config
        )
      } {

        // first request is handled with the only stall in the system
        val firstInquiry = ParkingInquiry(coordCenterOfUTM, "work")
        val expectedFirstStall =
          ParkingStall(
            Id.create(1, classOf[TAZ]),
            Id.create(1, classOf[TAZ]),
            0,
            coordCenterOfUTM,
            12.34,
            None,
            Some(PricingModel.FlatFee(12.34)),
            ParkingType.Workplace,
            VehicleManager.privateVehicleManager.managerId
          )
        val response1 = zonalParkingManager.processParkingInquiry(firstInquiry)
        assert(response1.isDefined, "no response")
        assert(
          response1.get == ParkingInquiryResponse(expectedFirstStall, firstInquiry.requestId),
          "something is wildly broken"
        )

        // since only stall is in use, the second inquiry will be handled with the emergency stall
        val secondInquiry = ParkingInquiry(coordCenterOfUTM, "work")
        val response2 @ Some(ParkingInquiryResponse(stall, responseId)) =
          zonalParkingManager.processParkingInquiry(secondInquiry)
        assert(response2.isDefined, "no response")
        assert(stall.geoId == TAZ.EmergencyTAZId && responseId == secondInquiry.requestId, "something is wildly broken")
      }
    }
  }

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
        oneParkingOption: Iterator[String] = """taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents
                                               |1,Workplace,FlatFee,UltraFast(250|DC),9999,5678,
          """.stripMargin.split("\n").toIterator
        zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(
          tazTreeMap,
          geo,
          oneParkingOption,
          boundingBox,
          new Random(randomSeed),
          managers,
          config
        )
      } {
        val vehicleType1 = beamScenario.vehicleTypes(Id.create("BEV_XFC", classOf[BeamVehicleType]))
        val vehicle1 = new BeamVehicle(
          id = Id.createVehicleId("car-01"),
          powerTrain = new Powertrain(0.0),
          beamVehicleType = vehicleType1,
          managerId = VehicleManager.privateVehicleManager.managerId
        )
        val xfcChargingPoint = CustomChargingPoint("ultrafast", 250.0, ElectricCurrentType.DC)
        // first request is handled with the only stall in the system
        val firstInquiry = ParkingInquiry(coordCenterOfUTM, "work", Some(vehicle1))
        val expectedFirstStall =
          ParkingStall(
            Id.create(1, classOf[TAZ]),
            Id.create(1, classOf[TAZ]),
            0,
            coordCenterOfUTM,
            56.78,
            Some(xfcChargingPoint),
            Some(PricingModel.FlatFee(12.34)),
            ParkingType.Workplace,
            VehicleManager.privateVehicleManager.managerId
          )
        val response1 = zonalParkingManager.processParkingInquiry(firstInquiry)
        assert(response1.isDefined, "no response")
        assert(response1.get.requestId == firstInquiry.requestId, "something is wildly broken")
        assert(response1.get.stall.toString == expectedFirstStall.toString, "something is wildly broken")

        // since only stall is in use, the second inquiry will be handled with the emergency stall
        val vehicleType2 = beamScenario.vehicleTypes(Id.create("BEV", classOf[BeamVehicleType]))
        val vehicle2 = new BeamVehicle(
          id = Id.createVehicleId("car-01"),
          powerTrain = new Powertrain(0.0),
          beamVehicleType = vehicleType2,
          managerId = VehicleManager.privateVehicleManager.managerId
        )
        val secondInquiry = ParkingInquiry(coordCenterOfUTM, "work", Some(vehicle2))
        val response2 @ Some(ParkingInquiryResponse(stall, responseId)) =
          zonalParkingManager.processParkingInquiry(secondInquiry)
        assert(response2.isDefined, "no response")
        assert(stall.geoId == TAZ.EmergencyTAZId && responseId == secondInquiry.requestId, "something is wildly broken")
        assert(stall.chargingPointType.isEmpty, "it should not get an Ultra Fast charging point stall")
      }
    }
  }

  describe("ZonalParkingManager with one parking option") {
    it("should allow us to book and then release that stall") {

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
        oneParkingOption: Iterator[String] = """taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents,reservedFor
          |1,Workplace,FlatFee,None,1,1234,
          |
          """.stripMargin.split("\n").toIterator
        zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(
          tazTreeMap,
          geo,
          oneParkingOption,
          boundingBox,
          new Random(randomSeed),
          managers,
          config
        )
      } {
        // note: ParkingInquiry constructor has a side effect of creating a new (unique) request id
        val firstInquiry = ParkingInquiry(coordCenterOfUTM, "work")
        val secondInquiry = ParkingInquiry(coordCenterOfUTM, "work")
        val expectedParkingZoneId = 0
        val expectedTAZId = Id.create(1, classOf[TAZ])
        val expectedStall =
          ParkingStall(
            expectedTAZId,
            expectedTAZId,
            expectedParkingZoneId,
            coordCenterOfUTM,
            12.34,
            None,
            Some(PricingModel.FlatFee(12.34)),
            ParkingType.Workplace,
            VehicleManager.privateVehicleManager.managerId
          )

        // request the stall
        val response1 = zonalParkingManager.processParkingInquiry(firstInquiry)
        assert(response1.isDefined, "no response")
        assert(
          response1.get == ParkingInquiryResponse(expectedStall, firstInquiry.requestId),
          "something is wildly broken"
        )

        // release the stall
        val releaseParkingStall = ReleaseParkingStall(expectedStall)
        zonalParkingManager.processReleaseParkingStall(releaseParkingStall)

        // request the stall again
        val response2 = zonalParkingManager.processParkingInquiry(secondInquiry)
        assert(response2.isDefined, "no response")
        assert(
          response2.get == ParkingInquiryResponse(expectedStall, secondInquiry.requestId),
          "something is wildly broken"
        )
      }
    }
  }

  describe("ZonalParkingManager with a known set of parking alternatives") {
    it("should allow us to book all of those options and then provide us emergency stalls after that point") {

      val random = new Random(1)

      // run this many trials of this test
      val trials = 1
      // the maximum number of parking stalls across all TAZs in each trial
      val maxParkingStalls = 10000
      // make inquiries (demand) over-saturate parking availability (supply)
      val maxInquiries = (maxParkingStalls.toDouble * 1.25).toInt

      // four square TAZs in a grid
      val tazList: List[(Coord, Double)] = List(
        (new Coord(25, 25), 2500),
        (new Coord(75, 25), 2500),
        (new Coord(25, 75), 2500),
        (new Coord(75, 75), 2500)
      )
      val middleOfWorld = new Coord(50, 50)

      for {
        trial <- 1 to trials
        numStalls = math.max(4, random.nextInt(maxParkingStalls))
        tazTreeMap <- ZonalParkingManagerSpec.mockTazTreeMap(tazList, startAtId = 1, 0, 0, 100, 100)
        split = ZonalParkingManagerSpec.randomSplitOfMaxStalls(numStalls, 4, random)
        parkingConfiguration: Iterator[String] = ZonalParkingManagerSpec.makeParkingConfiguration(split)
        config = BeamConfig(system.settings.config)
        zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(
          tazTreeMap,
          geo,
          parkingConfiguration,
          boundingBox,
          new Random(randomSeed),
          managers,
          config
        )
      } {

        val wasProvidedNonEmergencyParking: Iterable[Int] = for {
          _ <- 1 to maxInquiries
          req = ParkingInquiry(middleOfWorld, "work")
          response1 = zonalParkingManager.processParkingInquiry(req)
          counted = response1 match {
            case Some(res @ ParkingInquiryResponse(_, _)) =>
              if (res.stall.geoId != TAZ.EmergencyTAZId) 1 else 0
            case _ =>
              assert(response1.isDefined, "no response")
              0
          }
        } yield {
          counted
        }

        // if we counted how many inquiries were handled with non-emergency stalls, we can confirm this should match the numStalls
        // since we intentionally over-saturated parking demand
        val numWithNonEmergencyParking =
          if (wasProvidedNonEmergencyParking.nonEmpty) wasProvidedNonEmergencyParking.sum else 0
        numWithNonEmergencyParking should be(numStalls)
      }
    }
  }

  describe("ZonalParkingManager with loaded common data") {
    it("should return the correct stall") {
      val source = Source.fromFile("test/input/beamville/parking/taz-parking.csv")
      val parkingDescription: Iterator[String] = source.getLines()
      val tazMap = taz.TAZTreeMap.fromCsv("test/input/beamville/taz-centers.csv")
      val minSearchRadius = 1000.0
      val maxSearchRadius = 16093.4 // meters, aka 10 miles
      val zpm = ZonalParkingManager(
        parkingDescription,
        tazMap.tazQuadTree,
        tazMap.idToTAZMapping,
        identity[TAZ](_),
        geo,
        new Random(randomSeed),
        minSearchRadius,
        maxSearchRadius,
        boundingBox,
        vehicleManagers = managers,
        chargingPointConfig = beamConfig.beam.agentsim.chargingNetworkManager.chargingPoint
      )

      assertParkingResponse(
        zpm,
        new Coord(170308.0, 2964.0),
        "4",
        17,
        Block(0.0, 3600),
        ParkingType.Public,
        VehicleManager.privateVehicleManager.managerId
      )

      assertParkingResponse(
        zpm,
        new Coord(166321.0, 1568.0),
        "1",
        122,
        Block(0.0, 3600),
        ParkingType.Public,
        VehicleManager.privateVehicleManager.managerId
      )

      assertParkingResponse(
        zpm,
        new Coord(166500.0, 1500.0),
        "1",
        22,
        FlatFee(0.0),
        ParkingType.Residential,
        VehicleManager.privateVehicleManager.managerId
      )

      source.close()
    }
  }

  describe("ZonalParkingManager with multiple parking files loaded") {
    it("should return the correct stall corresponding with the request (reservedFor, vehicleManagerId)") {
      val sharedFleet1 = Id.create("shared-fleet-1", classOf[VehicleManager])
      val sharedFleet2 = Id.create("shared-fleet-2", classOf[VehicleManager])
      val managers2 = Map[Id[VehicleManager], VehicleManager](
        VehicleManager.privateVehicleManager.managerId -> VehicleManager.privateVehicleManager,
        sharedFleet1                                   -> VehicleManager.create(sharedFleet1, Some(VehicleCategory.Car), isShared = true),
        sharedFleet2                                   -> VehicleManager.create(sharedFleet2, Some(VehicleCategory.Car), isShared = true)
      )
      val parkingFilePaths = Map(
        VehicleManager.privateVehicleManager.managerId -> "test/test-resources/beam/agentsim/infrastructure/taz-parking.csv",
        sharedFleet1                                   -> "test/test-resources/beam/agentsim/infrastructure/taz-parking-shared-fleet-1.csv",
        sharedFleet2                                   -> "test/test-resources/beam/agentsim/infrastructure/taz-parking-shared-fleet-2.csv",
      )
      val tazMap = taz.TAZTreeMap.fromCsv("test/input/beamville/taz-centers.csv")
      val zpm = ZonalParkingManager(
        beamConfig,
        tazMap.tazQuadTree,
        tazMap.idToTAZMapping,
        identity[TAZ](_),
        geo,
        boundingBox,
        parkingFilePaths,
        managers2
      )

      assertParkingResponse(
        zpm,
        new Coord(170308.0, 2964.0),
        "4",
        73,
        FlatFee(1.99),
        ParkingType.Residential,
        vehicleManagerId = VehicleManager.privateVehicleManager.managerId
      )

      assertVehicleManager(
        zpm,
        new Coord(166321.0, 1568.0),
        VehicleManager.privateVehicleManager.managerId :: sharedFleet1 :: Nil,
        vehicleManagerId = sharedFleet1
      )

      assertVehicleManager(
        zpm,
        new Coord(166500.0, 1500.0),
        VehicleManager.privateVehicleManager.managerId :: sharedFleet2 :: Nil,
        vehicleManagerId = sharedFleet2
      )

    }
  }

  private def assertVehicleManager(
    zpm: ParkingNetwork,
    coord: Coord,
    zonalVehicleManagers: List[Id[VehicleManager]],
    vehicleManagerId: Id[VehicleManager]
  ) = {
    val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
    val vehicle = new BeamVehicle(
      id = Id.createVehicleId("car-01"),
      powerTrain = new Powertrain(0.0),
      beamVehicleType = vehicleType,
      managerId = vehicleManagerId
    )
    val inquiry = ParkingInquiry(coord, "init", Some(vehicle))
    val response = zpm.processParkingInquiry(inquiry)
    assert(response.isDefined, "no response")
    assert(zonalVehicleManagers.contains(response.get.stall.managerId), "something is wildly broken")
  }

  private def assertParkingResponse(
    zpm: ParkingNetwork,
    coord: Coord,
    tazId: String,
    parkingZoneId: Int,
    pricingModel: PricingModel,
    parkingType: ParkingType,
    vehicleManagerId: Id[VehicleManager]
  ) = {
    val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
    val vehicle = new BeamVehicle(
      id = Id.createVehicleId("car-01"),
      powerTrain = new Powertrain(0.0),
      beamVehicleType = vehicleType,
      managerId = vehicleManagerId
    )
    val inquiry = ParkingInquiry(coord, "init", Some(vehicle))
    val response = zpm.processParkingInquiry(inquiry)
    val tazId1 = Id.create(tazId, classOf[TAZ])
    val costInDollars = if (pricingModel.isInstanceOf[FlatFee]) pricingModel.costInDollars else 0.0
    val expectedStall =
      ParkingStall(
        tazId1,
        tazId1,
        parkingZoneId,
        coord,
        costInDollars,
        None,
        Some(pricingModel),
        parkingType,
        vehicleManagerId
      )
    assert(response.isDefined, "no response")
    assert(response.get == ParkingInquiryResponse(expectedStall, inquiry.requestId), "something is wildly broken")
  }

  override def afterAll: Unit = {
    shutdown()
  }
}

object ZonalParkingManagerSpec {

  def mockZonalParkingManager(
    tazTreeMap: TAZTreeMap,
    geo: GeoUtils,
    parkingDescription: Iterator[String],
    boundingBox: Envelope,
    random: Random = Random,
    managers: Map[Id[VehicleManager], VehicleManager],
    beamConfig: BeamConfig
  ): ZonalParkingManager[TAZ] = {
    val minSearchRadius = 1000.0
    val maxSearchRadius = 16093.4 // meters, aka 10 miles
    ZonalParkingManager(
      parkingDescription,
      tazTreeMap.tazQuadTree,
      tazTreeMap.idToTAZMapping,
      identity[TAZ](_),
      geo,
      random,
      minSearchRadius,
      maxSearchRadius,
      boundingBox,
      vehicleManagers = managers,
      chargingPointConfig = beamConfig.beam.agentsim.chargingNetworkManager.chargingPoint
    )
  }

  /**
    * creates a mock TAZTreeMap from a list of coordinate/geo area pairs
    * @param coords a list of coordinates paired with the area for the TAZ
    * @param startAtId name each TAZ by integer id ascending, starting from this number
    * @return a mock TAZTreeMap, or nothing
    */
  def mockTazTreeMap(
    coords: List[(Coord, Double)],
    startAtId: Int,
    xMin: Double,
    yMin: Double,
    xMax: Double,
    yMax: Double
  ): Option[TAZTreeMap] = {
    if (coords.isEmpty) None
    else {
      val quadTree = coords.foldLeft(new QuadTree[TAZ](xMin, yMin, xMax, yMax)) { (tree, tazData) =>
        val (coord, area) = tazData
        val tazId = Id.create(startAtId + tree.size, classOf[TAZ])
        val taz = new TAZ(tazId, coord, area)
        tree.put(coord.getX, coord.getY, taz)
        tree
      }
      Some { new TAZTreeMap(quadTree) }
    }
  }

  // comes up with a random n-way split of numStalls
  def randomSplitOfMaxStalls(numStalls: Int, numSplits: Int, random: Random): List[Int] = {
    @tailrec
    def _sample(remaining: Int, split: List[Int] = List.empty): List[Int] = {
      if (split.length == numSplits - 1) (numStalls - split.sum) +: split
      else {
        val nextSample =
          if (remaining > split.size) random.nextInt(remaining - split.size)
          else if (remaining == numSplits - split.size) 1
          else 0
        val nextRemaining = remaining - nextSample
        _sample(nextRemaining, nextSample +: split)
      }
    }
    if (numStalls <= 0 || numSplits < 1) List.empty
    else {
      val result = _sample(numStalls)
      result
    }
  }

  // using a split of numStalls, create a parking input for all-work, $0-cost parking alternatives with varying stall counts
  def makeParkingConfiguration(split: List[Int]): Iterator[String] = {
    val header = "taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents,reservedFor"
    val result = split.zipWithIndex
      .map {
        case (stalls, i) =>
          s"${i + 1},Workplace,FlatFee,None,$stalls,0,"
      }
      .mkString(s"$header\n", "\n", "")
      .split("\n")
      .toIterator
    result
  }

  def makeParkingZones(treeMap: TAZTreeMap, zones: List[Int]): Array[ParkingZone[TAZ]] = {
    val result = treeMap.getTAZs
      .zip(zones)
      .foldLeft(List.empty[ParkingZone[TAZ]]) {
        case (acc, (taz, numZones)) =>
          val parkingZones = (0 until numZones)
            .map(
              i =>
                ParkingZone(
                  acc.size + i,
                  taz.tazId,
                  ParkingType.Workplace,
                  5,
                  VehicleManager.privateVehicleManager.managerId,
                  None,
                  Some(FlatFee(3.0))
              )
            )
          acc ++ parkingZones
      }
    result.toArray
  }
}
