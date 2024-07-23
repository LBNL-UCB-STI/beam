package beam.agentsim.infrastructure

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKitBase}
import akka.util.Timeout
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.BeamvilleFixtures
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleManager}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.parking.PricingModel.{Block, FlatFee}
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
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

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.io.Source
import scala.util.Random

class ZonalParkingManagerSpec
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

  lazy implicit val system: ActorSystem = ActorSystem("PersonAndTransitDriverSpec", config)
  override def outputDirPath: String = TestConfigUtils.testOutputDir

  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)

  val randomSeed: Int = 0

  // a coordinate in the center of the UTM coordinate system
  val coordCenterOfUTM = new Coord(500000, 5000000)
  val centerSpaceTime: SpaceTime = SpaceTime(coordCenterOfUTM, 0)

  val geo = new GeoUtilsImpl(beamConfig)

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
        emptyParkingDescription: Iterator[String] = Iterator.single(ParkingZoneFileUtils.ParkingFileHeader)
        zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(
          config,
          tazTreeMap,
          geo,
          emptyParkingDescription,
          boundingBox,
          randomSeed
        )
      } {
        val inquiry = ParkingInquiry.init(centerSpaceTime, "work", triggerId = 77239)
        val expectedStall: ParkingStall = ParkingStall.lastResortStall(
          new Envelope(
            inquiry.destinationUtm.loc.getX + 2000,
            inquiry.destinationUtm.loc.getX - 2000,
            inquiry.destinationUtm.loc.getY + 2000,
            inquiry.destinationUtm.loc.getY - 2000
          ),
          new Random(randomSeed)
        )

        val response = zonalParkingManager.processParkingInquiry(inquiry)

        // note on the random seed:
        // since there are no TAZs to search and sample parking locations from,
        // the random number generator is unused by the [[ZonalParkingManager]] search, and we can
        // therefore rely on the coordinate that is generated when [[ZonalParkingManager]] calls [[ParkingStall.emergencyStall]] internally
        assert(
          response == ParkingInquiryResponse(expectedStall, inquiry.requestId, inquiry.triggerId),
          "something is wildly broken"
        )
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
      } {

        // first request is handled with the only stall in the system
        val firstInquiry =
          ParkingInquiry.init(centerSpaceTime, "work", triggerId = 3234324)
        val expectedFirstStall =
          ParkingStall(
            Id.create(1, classOf[TAZ]),
            ParkingZone.createId("0"),
            coordCenterOfUTM,
            12.34,
            None,
            Some(PricingModel.FlatFee(12.34)),
            ParkingType.Workplace,
            VehicleManager.AnyManager
          )
        val response1 = zonalParkingManager.processParkingInquiry(firstInquiry)
        assert(
          response1 == ParkingInquiryResponse(expectedFirstStall, firstInquiry.requestId, firstInquiry.triggerId),
          "something is wildly broken"
        )

        // since only stall is in use, the second inquiry will be handled with the emergency stall
        val secondInquiry =
          ParkingInquiry.init(centerSpaceTime, "work", triggerId = 123709)
        val _ @ParkingInquiryResponse(stall, responseId, triggerId) =
          zonalParkingManager.processParkingInquiry(secondInquiry)
        assert(
          stall.tazId == TAZ.EmergencyTAZId && responseId == secondInquiry.requestId
          && triggerId == secondInquiry.triggerId,
          "something is wildly broken"
        )
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
      } {
        // note: ParkingInquiry constructor has a side effect of creating a new (unique) request id
        val firstInquiry = ParkingInquiry.init(centerSpaceTime, "work", triggerId = 3829)
        val secondInquiry =
          ParkingInquiry.init(centerSpaceTime, "work", triggerId = 38429)
        val expectedTAZId = Id.create(1, classOf[TAZ])
        val expectedStall =
          ParkingStall(
            expectedTAZId,
            ParkingZone.createId("0"),
            coordCenterOfUTM,
            12.34,
            None,
            Some(PricingModel.FlatFee(12.34)),
            ParkingType.Workplace,
            VehicleManager.AnyManager
          )

        // request the stall
        val response1 = zonalParkingManager.processParkingInquiry(firstInquiry)
        assert(
          response1 == ParkingInquiryResponse(expectedStall, firstInquiry.requestId, firstInquiry.triggerId),
          "something is wildly broken"
        )

        // release the stall
        val releaseParkingStall = ReleaseParkingStall(expectedStall, 0)
        zonalParkingManager.processReleaseParkingStall(releaseParkingStall)

        // request the stall again
        val response2 = zonalParkingManager.processParkingInquiry(secondInquiry)
        assert(
          response2 == ParkingInquiryResponse(expectedStall, secondInquiry.requestId, secondInquiry.triggerId),
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
        _ <- 1 to trials
        numStalls = math.max(4, random.nextInt(maxParkingStalls))
        tazTreeMap <- ZonalParkingManagerSpec.mockTazTreeMap(tazList, startAtId = 1, 0, 0, 100, 100)
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
      } {

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
        tazMap,
        boundingBox,
        geo.distUTMInMeters(_, _),
        minSearchRadius,
        maxSearchRadius,
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

      source.close()
    }
  }

  describe("ZonalParkingManager with time restrictions") {
    it("should return a stall from the single available zone (index=2)") {
      val parkingDescription: Iterator[String] =
        """taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,timeRestrictions,reservedFor,parkingZoneId
          |4,Public,FlatFee,NoCharger,10,0,Car|0-17:30;Class456Vocational|17:31-23:59,,a
          |4,Public,Block,NoCharger,20,0,Class456Vocational|0-17:30;Car|17:31-23:59,,b""".stripMargin
          .split("\n")
          .toIterator
      val tazMap = taz.TAZTreeMap.fromCsv("test/input/beamville/taz-centers.csv")
      val minSearchRadius = 1000.0
      val maxSearchRadius = 16093.4 // meters, aka 10 miles
      val zpm = ZonalParkingManager(
        parkingDescription,
        tazMap,
        boundingBox,
        geo.distUTMInMeters(_, _),
        minSearchRadius,
        maxSearchRadius,
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

  describe("ZonalParkingManager with multiple parking files loaded") {
    it("should return the correct stall corresponding with the request (reservedFor, vehicleManagerId)") {
      val sharedFleet1 = VehicleManager.createOrGetReservedFor("shared-fleet-1", VehicleManager.TypeEnum.Shared)
      val sharedFleet2 = VehicleManager.createOrGetReservedFor("shared-fleet-2", VehicleManager.TypeEnum.Shared)
      val tazMap = taz.TAZTreeMap.fromCsv("test/input/beamville/taz-centers.csv")
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
        beamConfig.beam.agentsim.agents.parking.minSearchRadius,
        beamConfig.beam.agentsim.agents.parking.maxSearchRadius,
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
  ) = {
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
    assert(vehicleManagerToAssert.contains(response.stall.reservedFor), "something is wildly broken")
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
  ) = {
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
    val expectedStall =
      ParkingStall(
        tazId1,
        parkingZoneId,
        spaceTime.loc,
        costInDollars,
        None,
        Some(pricingModel),
        parkingType,
        reservedFor = reservedFor
      )
    assert(
      response == ParkingInquiryResponse(expectedStall, inquiry.requestId, inquiry.triggerId),
      "something is wildly broken"
    )
  }

  override def afterAll(): Unit = {
    shutdown()
  }
}

object ZonalParkingManagerSpec {

  def mockZonalParkingManager(
    beamConfig: BeamConfig,
    tazTreeMap: TAZTreeMap,
    geo: GeoUtils,
    parkingDescription: Iterator[String],
    boundingBox: Envelope,
    seed: Int
  ): ZonalParkingManager = {
    val minSearchRadius = 1000.0
    val maxSearchRadius = 16093.4 // meters, aka 10 miles
    ZonalParkingManager(
      parkingDescription,
      tazTreeMap,
      boundingBox,
      geo.distUTMInMeters(_, _),
      minSearchRadius,
      maxSearchRadius,
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
    yMax: Double
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
