package beam.agentsim.infrastructure

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKitBase}
import akka.util.Timeout
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.BeamvilleFixtures
import beam.agentsim.agents.freight.FreightActivityType
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleManager}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ParkingInquiry.{ParkingActivityType, ParkingSearchMode}
import beam.agentsim.infrastructure.parking.PricingModel.{Block, FlatFee}
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.{SearchQuadTree, TAZ, TAZTreeMap}
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{SimRunnerForTest, TestConfigUtils}
import com.typesafe.config.{Config, ConfigFactory}
import org.locationtech.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.scalatest.funspec.AnyFunSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.io.Source
import scala.util.{Random, Try, Using}

class ZonalParkingManagerSpec
    extends AnyFunSpecLike
    with TestKitBase
    with SimRunnerForTest
    with ImplicitSender
    with Matchers
    with BeamvilleFixtures
    with BeforeAndAfterAll
    with BeforeAndAfterEach {
  import ZonalParkingManagerSpec.searchDistancesConfig

  lazy val config: Config = ConfigFactory
    .parseString(
      """
        akka.log-dead-letters = 10
        akka.actor.debug.fsm = true
        akka.loglevel = debug
        akka.test.timefactor = 2
        beam.agentsim.agents.freight {
          enabled = true
          plansFilePath = ${beam.inputDirectory}"/freight/payload-plans.csv"
          toursFilePath = ${beam.inputDirectory}"/freight/freight-tours.csv"
          carriersFilePath = ${beam.inputDirectory}"/freight/freight-carriers.csv"
          carrierParkingFilePath = ${beam.inputDirectory}"/freight/freight-depots.csv"
          vehicleTypesFilePath = ${beam.inputDirectory}"/freight/freight-vehicleTypes.csv"
          reader = "Generic"
        }
        """
    )
    .withFallback(testConfig("test/input/beamville/beam.conf"))
    .resolve()

  lazy implicit val system: ActorSystem = ActorSystem("ZonalParkingManagerSpec", config)
  override def outputDirPath: String = TestConfigUtils.testOutputDir

  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)

  val randomSeed: Int = 0

  // a coordinate in the center of the UTM coordinate system
  val coordCenterOfUTM = new Coord(500000, 5000000)
  val centerSpaceTime: SpaceTime = SpaceTime(coordCenterOfUTM, 0)

  val geo = new GeoUtilsImpl(beamConfig)

  // Helper method to check if test files exist
  private def testFilesExist: Boolean = {
    val requiredFiles = List(
      "test/input/beamville/parking/taz-parking.csv",
      "test/input/beamville/taz-centers.csv",
      "test/test-resources/beam/agentsim/infrastructure/taz-parking.csv",
      "test/test-resources/beam/agentsim/infrastructure/taz-parking-shared-fleet-1.csv",
      "test/test-resources/beam/agentsim/infrastructure/taz-parking-shared-fleet-2.csv"
    )
    requiredFiles.forall(path => new java.io.File(path).exists())
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    // Reset any global state if needed
  }

  override def afterAll(): Unit = {
    Try(shutdown())
    super.afterAll()
  }

  describe("ZonalParkingManager with no parking") {
    it("should return a response with an emergency stall") {
      val result = for {
        tazTreeMap <- ZonalParkingManagerSpec.mockTazTreeMap(
          coords = List((coordCenterOfUTM, 10000)),
          startAtId = 1,
          xMin = 167000,
          yMin = 0,
          xMax = 833000,
          yMax = 10000000,
          scenarioCRS = geo.localCRS
        ) // one TAZ at agent coordinate
        config = beamConfig
        emptyParkingDescription: Iterator[String] = Iterator.single(ParkingZoneFileUtils.ParkingFileHeader)
        zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(
          config,
          tazTreeMap,
          geo,
          emptyParkingDescription,
          boundingBox,
          randomSeed
        )
      } yield {
        val inquiry = ParkingInquiry.init(centerSpaceTime, "work", triggerId = 77239)
        val (expectedStall: ParkingStall, _) =
          ParkingStall.lastResortStall(inquiry.destinationUtm.loc, new Random(randomSeed), ParkingActivityType.Working)

        val response = zonalParkingManager.processParkingInquiry(inquiry)

        response shouldBe ParkingInquiryResponse(expectedStall, inquiry.requestId, inquiry.triggerId)
      }

      result shouldBe defined
    }

    describe("when given a double parking allowed inquiry") {
      it("should return a double parking response") {
        val result = for {
          tazTreeMap <- ZonalParkingManagerSpec.mockTazTreeMap(
            coords = List((coordCenterOfUTM, 10000)),
            startAtId = 1,
            xMin = 167000,
            yMin = 0,
            xMax = 833000,
            yMax = 10000000,
            scenarioCRS = geo.localCRS
          )
          config = beamConfig
          emptyParkingDescription: Iterator[String] = Iterator.single(ParkingZoneFileUtils.ParkingFileHeader)
          zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(
            config,
            tazTreeMap,
            geo,
            emptyParkingDescription,
            boundingBox,
            randomSeed
          )
        } yield {
          val inquiry = ParkingInquiry.init(
            centerSpaceTime,
            FreightActivityType.Unloading.toString,
            searchMode = ParkingSearchMode.DoubleParkingAllowed,
            triggerId = 77239
          )
          val response = zonalParkingManager.processParkingInquiry(inquiry)

          response.requestId shouldBe inquiry.requestId
          response.triggerId shouldBe inquiry.triggerId
          response.stall.tazId shouldBe Id.create(1, classOf[TAZ])
          response.stall.locationUTM shouldBe inquiry.destinationUtm.loc
          response.stall.chargingPointType shouldBe None
          response.stall.parkingType shouldBe ParkingType.DoubleParking
          response.stall.parkingZoneId shouldBe ParkingZone.ObstructiveParkingZone.parkingZoneId
        }

        result shouldBe defined
      }
    }
  }

  describe("ZonalParkingManager with one parking option") {
    it("should first return that only stall, and afterward respond with the default stall") {
      val result = for {
        tazTreeMap <- ZonalParkingManagerSpec.mockTazTreeMap(
          List((coordCenterOfUTM, 10000)),
          startAtId = 1,
          167000,
          0,
          833000,
          10000000,
          scenarioCRS = geo.localCRS
        ) // one TAZ at agent coordinate
        config = BeamConfig(system.settings.config)
        oneParkingOption: Iterator[String] = s"""taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor,parkingZoneId
                                                |1,Workplace,FlatFee,None,1,1234,,0
                                                |
          """.stripMargin.split("\n").toIterator
        zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(
          config,
          tazTreeMap,
          geo,
          oneParkingOption,
          boundingBox,
          randomSeed
        )
      } yield {
        // first request is handled with the only stall in the system
        val firstInquiry = ParkingInquiry.init(centerSpaceTime, "work", triggerId = 3234324)
        val expectedFirstStall = ParkingStall(
          Id.create(1, classOf[TAZ]),
          ParkingZone.createId("0"),
          coordCenterOfUTM,
          12.34,
          None,
          Some(PricingModel.FlatFee(12.34)),
          ParkingType.Workplace,
          ParkingActivityType.Working,
          VehicleManager.AnyManager
        )
        val response1 = zonalParkingManager.processParkingInquiry(firstInquiry)

        response1 shouldBe ParkingInquiryResponse(expectedFirstStall, firstInquiry.requestId, firstInquiry.triggerId)

        // since only stall is in use, the second inquiry will be handled with the emergency stall
        val secondInquiry = ParkingInquiry.init(centerSpaceTime, "work", triggerId = 123709)
        val ParkingInquiryResponse(stall, responseId, triggerId) =
          zonalParkingManager.processParkingInquiry(secondInquiry)

        stall.tazId shouldBe TAZ.EmergencyTAZId
        responseId shouldBe secondInquiry.requestId
        triggerId shouldBe secondInquiry.triggerId
      }

      result shouldBe defined
    }
  }

  describe("ZonalParkingManager with one parking option") {
    it("should allow us to book and then release that stall") {
      val result = for {
        tazTreeMap <- ZonalParkingManagerSpec.mockTazTreeMap(
          List((coordCenterOfUTM, 10000)),
          startAtId = 1,
          167000,
          0,
          833000,
          10000000,
          scenarioCRS = geo.localCRS
        ) // one TAZ at agent coordinate
        config = BeamConfig(system.settings.config)
        oneParkingOption: Iterator[String] =
          """taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor,parkingZoneId
            |1,Workplace,FlatFee,None,1,1234,,0
            |
          """.stripMargin.split("\n").toIterator
        zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(
          config,
          tazTreeMap,
          geo,
          oneParkingOption,
          boundingBox,
          randomSeed
        )
      } yield {
        // note: ParkingInquiry constructor has a side effect of creating a new (unique) request id
        val firstInquiry = ParkingInquiry.init(centerSpaceTime, "work", triggerId = 3829)
        val secondInquiry = ParkingInquiry.init(centerSpaceTime, "work", triggerId = 38429)
        val expectedTAZId = Id.create(1, classOf[TAZ])
        val expectedStall = ParkingStall(
          expectedTAZId,
          ParkingZone.createId("0"),
          coordCenterOfUTM,
          12.34,
          None,
          Some(PricingModel.FlatFee(12.34)),
          ParkingType.Workplace,
          ParkingActivityType.Working,
          VehicleManager.AnyManager
        )

        // request the stall
        val response1 = zonalParkingManager.processParkingInquiry(firstInquiry)
        response1 shouldBe ParkingInquiryResponse(expectedStall, firstInquiry.requestId, firstInquiry.triggerId)

        // release the stall
        val releaseParkingStall = ReleaseParkingStall(expectedStall, 0)
        zonalParkingManager.processReleaseParkingStall(releaseParkingStall)

        // request the stall again
        val response2 = zonalParkingManager.processParkingInquiry(secondInquiry)
        response2 shouldBe ParkingInquiryResponse(expectedStall, secondInquiry.requestId, secondInquiry.triggerId)
      }

      result shouldBe defined
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

      val results = for {
        _ <- 1 to trials
        numStalls = math.max(4, random.nextInt(maxParkingStalls))
        tazTreeMap <- ZonalParkingManagerSpec.mockTazTreeMap(
          tazList,
          startAtId = 1,
          0,
          0,
          100,
          100,
          scenarioCRS = geo.localCRS
        )
        split = ZonalParkingManagerSpec.randomSplitOfMaxStalls(numStalls, 4, random)
        parkingConfiguration: Iterator[String] = ZonalParkingManagerSpec.makeParkingConfiguration(split)
        config = BeamConfig(system.settings.config)
        zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(
          config,
          tazTreeMap,
          geo,
          parkingConfiguration,
          boundingBox,
          randomSeed
        )
      } yield {
        val wasProvidedNonEmergencyParking: Iterable[Int] = for {
          _ <- 1 to maxInquiries
          req = ParkingInquiry.init(
            SpaceTime(middleOfWorld, 0),
            "work",
            triggerId = 839237
          )
          response1 = zonalParkingManager.processParkingInquiry(req)
          ParkingInquiryResponse(stall, _, _) = response1
          counted = if (stall.tazId != TAZ.EmergencyTAZId) 1 else 0
        } yield {
          counted
        }

        // if we counted how many inquiries were handled with non-emergency stalls, we can confirm this should match the numStalls
        // since we intentionally over-saturated parking demand
        val numWithNonEmergencyParking =
          if (wasProvidedNonEmergencyParking.nonEmpty) wasProvidedNonEmergencyParking.sum else 0
        numWithNonEmergencyParking shouldBe numStalls
      }

      results.length shouldBe trials
    }
  }

  describe("ZonalParkingManager with loaded common data") {
    it("should return the correct stall") {
      assume(testFilesExist, "Test data files not found - skipping file-dependent test")

      Using.resource(Source.fromFile("test/input/beamville/parking/taz-parking.csv")) { source =>
        val parkingDescription: Iterator[String] = source.getLines()
        val tazMap = taz.TAZTreeMap("test/input/beamville/taz-centers.csv", scenarioCRS = geo.localCRS)
        val zpm = ZonalParkingManager(
          parkingDescription,
          tazMap,
          boundingBox,
          geo.distUTMInMeters(_, _),
          searchDistancesConfig,
          randomSeed,
          beamConfig.beam.agentsim.agents.parking.multinomialLogit,
          beamConfig,
          None
        )

        assertParkingResponse(
          zpm,
          SpaceTime(new Coord(170308.0, 2964.0), 0),
          "4",
          ParkingZone.createId("17"),
          Block(0.0, 3600),
          ParkingType.Public,
          "beamVilleCar"
        )

        assertParkingResponse(
          zpm,
          SpaceTime(new Coord(166321.0, 1568.0), 0),
          "1",
          ParkingZone.createId("122"),
          Block(0.0, 3600),
          ParkingType.Public,
          "beamVilleCar"
        )

        assertParkingResponse(
          zpm,
          SpaceTime(new Coord(167141.3, 3326.017), 0),
          "2",
          ParkingZone.createId("14"),
          Block(0.0, 3600),
          ParkingType.Public,
          "beamVilleCar"
        )

        assertParkingResponse(
          zpm,
          SpaceTime(new Coord(167141.3, 3326.017), 1800),
          "2",
          ParkingZone.createId("115"),
          FlatFee(0.0),
          ParkingType.Public,
          "beamVilleCar"
        )
      }
    }
  }

  describe("ZonalParkingManager with time restrictions") {
    it("should return a stall from the single available zone (index=2)") {
      assume(testFilesExist, "Test data files not found - skipping file-dependent test")

      val parkingDescription: Iterator[String] =
        """taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,timeRestrictions,reservedFor,parkingZoneId
          |4,Public,FlatFee,NoCharger,10,0,Class12aVocational|0-17:30;Class456Vocational|17:31-23:59,,a
          |4,Public,Block,NoCharger,20,0,Class456Vocational|0-17:30;Class12aVocational|17:31-23:59,,b""".stripMargin
          .split("\n")
          .toIterator
      val tazMap = taz.TAZTreeMap("test/input/beamville/taz-centers.csv", scenarioCRS = geo.localCRS)
      val zpm = ZonalParkingManager(
        parkingDescription,
        tazMap,
        boundingBox,
        geo.distUTMInMeters(_, _),
        searchDistancesConfig,
        randomSeed,
        beamConfig.beam.agentsim.agents.parking.multinomialLogit,
        beamConfig,
        None
      )

      assertParkingResponse(
        zpm,
        SpaceTime(new Coord(169369.8, 3326.017), 8 * 3600),
        "4",
        ParkingZone.createId("b"),
        PricingModel("block", "0").get,
        ParkingType.Public,
        "FREIGHT-1"
      )
    }
  }

  describe("When no parking stalls at destination and a doubleParkingAllowed is true") {
    it("should return a double parking stall") {
      assume(testFilesExist, "Test data files not found - skipping file-dependent test")

      val parkingDescription: Iterator[String] =
        """taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,timeRestrictions,reservedFor,parkingZoneId
          |4,Public,FlatFee,NoCharger,1,0,,,a
          |4,Public,Block,NoCharger,1,0,,,b""".stripMargin
          .split("\n")
          .toIterator
      val tazMap = taz.TAZTreeMap("test/input/beamville/taz-centers.csv", scenarioCRS = geo.localCRS)
      val zpm = ZonalParkingManager(
        parkingDescription,
        tazMap,
        boundingBox,
        geo.distUTMInMeters(_, _),
        searchDistancesConfig,
        randomSeed,
        beamConfig.beam.agentsim.agents.parking.multinomialLogit,
        beamConfig,
        None
      )
      val taz4Location = new Coord(169369.8, 3326.017)
      // taking the only 2 stalls we have in parking manager
      zpm.processParkingInquiry(ParkingInquiry.init(SpaceTime(taz4Location, 8 * 3600), "Work", triggerId = 3737))
      zpm.processParkingInquiry(ParkingInquiry.init(SpaceTime(taz4Location, 8 * 3600), "Work", triggerId = 3738))
      // sending double parking response
      val inquiry = ParkingInquiry.init(
        SpaceTime(taz4Location, 8 * 3600),
        FreightActivityType.Unloading.toString,
        searchMode = ParkingSearchMode.DoubleParkingAllowed,
        triggerId = 3739
      )
      val response = zpm.processParkingInquiry(inquiry)
      response.requestId shouldBe inquiry.requestId
      response.triggerId shouldBe inquiry.triggerId
      response.stall.tazId shouldBe Id.create(4, classOf[TAZ])
      response.stall.locationUTM shouldBe inquiry.destinationUtm.loc
      response.stall.chargingPointType shouldBe None
      response.stall.parkingType shouldBe ParkingType.DoubleParking
      response.stall.parkingZoneId shouldBe ParkingZone.ObstructiveParkingZone.parkingZoneId
    }
  }

  describe("ZonalParkingManager with multiple parking files loaded") {
    it("should return the correct stall corresponding with the request (reservedFor, vehicleManagerId)") {
      assume(testFilesExist, "Test data files not found - skipping file-dependent test")

      val sharedFleet1 = VehicleManager.createOrGetReservedFor("shared-fleet-1", Some(VehicleManager.TypeEnum.Shared))
      val sharedFleet2 = VehicleManager.createOrGetReservedFor("shared-fleet-2", Some(VehicleManager.TypeEnum.Shared))
      val tazMap = taz.TAZTreeMap("test/input/beamville/taz-centers.csv", scenarioCRS = geo.localCRS)
      val stalls = InfrastructureUtils.loadStalls(
        "test/test-resources/beam/agentsim/infrastructure/taz-parking.csv",
        IndexedSeq(
          (
            "test/test-resources/beam/agentsim/infrastructure/taz-parking-shared-fleet-1.csv",
            sharedFleet1,
            Seq(ParkingType.Public)
          ),
          (
            "test/test-resources/beam/agentsim/infrastructure/taz-parking-shared-fleet-2.csv",
            sharedFleet2,
            Seq(ParkingType.Public)
          )
        ),
        null, //it is required only in case of failures
        1.0,
        1.0,
        randomSeed,
        beamConfig,
        None
      )
      val parkingZones = InfrastructureUtils.loadParkingStalls(stalls)
      val zonesMap = ZonalParkingManager(
        parkingZones,
        tazMap,
        geo.distUTMInMeters(_, _),
        boundingBox,
        beamConfig.beam.agentsim.agents.parking.search.params,
        beamConfig.beam.agentsim.agents.parking.fractionOfSameTypeZones,
        beamConfig.beam.agentsim.agents.parking.minNumberOfSameTypeZones,
        randomSeed,
        beamConfig.beam.agentsim.agents.parking.multinomialLogit,
        beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds
      )

      assertParkingResponse(
        zonesMap,
        SpaceTime(new Coord(170308.0, 2964.0), 0),
        "4",
        ParkingZone.createId("82"),
        FlatFee(1.99),
        ParkingType.Public,
        "beamVilleCar"
      )

      assertVehicleManager(
        zonesMap,
        new Coord(166321.0, 1568.0),
        sharedFleet1,
        Seq(sharedFleet1, VehicleManager.AnyManager)
      )

      assertVehicleManager(
        zonesMap,
        new Coord(166500.0, 1500.0),
        sharedFleet2,
        Seq(sharedFleet2, VehicleManager.AnyManager)
      )
    }
  }

  private def assertVehicleManager(
    zpm: ParkingNetwork,
    coord: Coord,
    reservedFor: ReservedFor,
    vehicleManagerToAssert: Seq[ReservedFor]
  ): Unit = {
    val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
    val vehicle = new BeamVehicle(
      id = Id.createVehicleId("car-01"),
      powerTrain = new Powertrain(0.0),
      beamVehicleType = vehicleType,
      vehicleManagerId = new AtomicReference(reservedFor.managerId)
    )
    vehicle.spaceTime = SpaceTime(coord.getX - 200, coord.getY - 200, 0)
    val inquiry = ParkingInquiry.init(SpaceTime(coord, 0), "init", reservedFor, Some(vehicle), triggerId = 0)
    val response = zpm.processParkingInquiry(inquiry)
    vehicleManagerToAssert should contain(response.stall.reservedFor)
  }

  private def assertParkingResponse(
    zpm: ParkingNetwork,
    spaceTime: SpaceTime,
    tazId: String,
    parkingZoneId: Id[ParkingZoneId],
    pricingModel: PricingModel,
    parkingType: ParkingType,
    vehicleTypeName: String,
    reservedFor: ReservedFor = VehicleManager.AnyManager
  ): Unit = {
    val vehicleType = beamScenario.vehicleTypes(Id.create(vehicleTypeName, classOf[BeamVehicleType]))
    val vehicle = new BeamVehicle(
      id = Id.createVehicleId("car-01"),
      powerTrain = new Powertrain(0.0),
      beamVehicleType = vehicleType,
      vehicleManagerId = new AtomicReference(reservedFor.managerId)
    )
    vehicle.spaceTime = SpaceTime(spaceTime.loc.getX - 200, spaceTime.loc.getY - 200, 0)
    val inquiry = ParkingInquiry.init(spaceTime, "init", reservedFor, Some(vehicle), triggerId = 3737)
    val response = zpm.processParkingInquiry(inquiry)
    val tazId1 = Id.create(tazId, classOf[TAZ])
    val costInDollars = PricingModel.evaluateParkingTicket(pricingModel, 60)
    val expectedStall = ParkingStall(
      tazId1,
      parkingZoneId,
      spaceTime.loc,
      costInDollars,
      None,
      Some(pricingModel),
      parkingType,
      ParkingActivityType.Miscellaneous,
      reservedFor = reservedFor
    )
    response shouldBe ParkingInquiryResponse(expectedStall, inquiry.requestId, inquiry.triggerId)
  }
}

object ZonalParkingManagerSpec {

  private val searchDistancesConfig = BeamConfig.Beam.Agentsim.Agents.Parking.Search.Params(
    freight =
      BeamConfig.Beam.Agentsim.Agents.Parking.Search.Params.Freight(minSearchRadius = 10.0, maxSearchRadius = 200.0),
    passenger = BeamConfig.Beam.Agentsim.Agents.Parking.Search.Params
      .Passenger(minSearchRadius = 1000.0, maxSearchRadius = 16093.4), // meters, aka 10 miles
    searchDoubleParkingRadius = 100.0,
    linkSearchMaxCandidateScan = 0,
    searchExpansionFactor = 1.5,
    searchMaxDistanceRelativeToEllipseFoci = 4.0,
    enableLinkBasedSearch = false,
    searchSampleSize = 500
  )

  def mockZonalParkingManager(
    beamConfig: BeamConfig,
    tazTreeMap: TAZTreeMap,
    geo: GeoUtils,
    parkingDescription: Iterator[String],
    boundingBox: Envelope,
    seed: Int
  ): ZonalParkingManager = {
    ZonalParkingManager(
      parkingDescription,
      tazTreeMap,
      boundingBox,
      geo.distUTMInMeters(_, _),
      searchDistancesConfig,
      seed,
      beamConfig.beam.agentsim.agents.parking.multinomialLogit,
      beamConfig,
      None
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
    yMax: Double,
    scenarioCRS: String
  ): Option[TAZTreeMap] = {
    if (coords.isEmpty) None
    else {
      val quadTree = coords.foldLeft(new QuadTree[TAZ](xMin, yMin, xMax, yMax)) { (tree, tazData) =>
        val (coord, area) = tazData
        val tazId = Id.create(startAtId + tree.size, classOf[TAZ])
        val taz = new TAZ(tazId, coord, area, None, None)
        tree.put(coord.getX, coord.getY, taz)
        tree
      }
      val tazTreeMap = new TAZTreeMap(quadTree, scenarioCRS = scenarioCRS)
      tazTreeMap.searchQuadTree = Some(SearchQuadTree.getSearchQuadTree(tazTreeMap, Map.empty))
      Some(tazTreeMap)
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
    val header = "taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor,parkingZoneId"
    val result = split.zipWithIndex
      .map { case (stalls, i) => s"${i + 1},Workplace,FlatFee,None,$stalls,0,," }
      .mkString(s"$header\n", "\n", "")
      .split("\n")
      .toIterator
    result
  }

  def makeParkingZones(
    treeMap: TAZTreeMap,
    zones: List[Int],
    reservedFor: ReservedFor
  ): Map[Id[ParkingZoneId], ParkingZone] = {
    val result = treeMap.getTAZs
      .zip(zones)
      .foldLeft(Map.empty[Id[ParkingZoneId], ParkingZone]) { case (acc, (taz, numZones)) =>
        val parkingZones = (0 until numZones).map { _ =>
          val zone = ParkingZone
            .init(
              None,
              taz.tazId,
              ParkingType.Workplace,
              reservedFor,
              5,
              pricingModel = Some(FlatFee(3.0))
            )
          zone.parkingZoneId -> zone
        }.toMap
        acc ++ parkingZones
      }
    result
  }
}
