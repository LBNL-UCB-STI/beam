package beam.agentsim.infrastructure

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKitBase}
import akka.util.Timeout
import beam.agentsim.agents.BeamvilleFixtures
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleManager}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ParkingInquiry.ParkingSearchMode
import beam.agentsim.infrastructure.charging.ChargingPointType.CustomChargingPoint
import beam.agentsim.infrastructure.charging.ElectricCurrentType
import beam.agentsim.infrastructure.parking.PricingModel.FlatFee
import beam.agentsim.infrastructure.parking.{ParkingType, ParkingZone, ParkingZoneId}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.router.skim.SkimsUtils
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
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.collectionAsScalaIterableConverter
import scala.util.Random

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
        beam.agentsim.agents.parking.minSearchRadius = 1000.0
        beam.agentsim.agents.parking.maxSearchRadius = 16093.4
        beam.agentsim.agents.parking.searchMaxDistanceRelativeToEllipseFoci = 4.0
        matsim.modules.global.randomSeed = 0
        """
    )
    .withFallback(testConfig("test/input/beamville/beam.conf"))
    .resolve()

  lazy implicit val system: ActorSystem = ActorSystem("PersonAndTransitDriverSpec", config)

  override def outputDirPath: String = TestConfigUtils.testOutputDir

  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)

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
          config,
          tazTreeMap,
          geo,
          oneParkingOption,
          boundingBox
        )
      } {
        val vehicleType1 = beamScenario.vehicleTypes(Id.create("BEV_XFC", classOf[BeamVehicleType]))
        val vehicle1 = new BeamVehicle(
          id = Id.createVehicleId("car-01"),
          powerTrain = new Powertrain(0.0),
          beamVehicleType = vehicleType1
        )
        vehicle1.spaceTime = SpaceTime(centerSpaceTime.loc.getX - 200, centerSpaceTime.loc.getY - 200, 0)
        val xfcChargingPoint = CustomChargingPoint("ultrafast", 250.0, ElectricCurrentType.DC)
        // first request is handled with the only stall in the system
        val firstInquiry =
          ParkingInquiry.init(centerSpaceTime, "charge", beamVehicle = Some(vehicle1), triggerId = 73737)
        val expectedFirstStall =
          ParkingStall(
            Id.create(1, classOf[TAZ]),
            ParkingZone.createId("0"),
            coordCenterOfUTM,
            56.78,
            Some(xfcChargingPoint),
            Some(FlatFee(56.78)),
            ParkingType.Workplace,
            VehicleManager.AnyManager
          )
        val response1 = chargingNetwork.processParkingInquiry(firstInquiry)
        assert(
          response1 == ParkingInquiryResponse(expectedFirstStall, firstInquiry.requestId, firstInquiry.triggerId),
          "something is wildly broken"
        )

        // since only stall is in use, the second inquiry will be handled with the emergency stall
        val vehicleType2 = beamScenario.vehicleTypes(Id.create("BEV", classOf[BeamVehicleType]))
        val vehicle2 = new BeamVehicle(
          id = Id.createVehicleId("car-01"),
          powerTrain = new Powertrain(0.0),
          beamVehicleType = vehicleType2
        )
        vehicle2.spaceTime = SpaceTime(centerSpaceTime.loc.getX - 200, centerSpaceTime.loc.getY - 200, 0)
        val secondInquiry =
          ParkingInquiry.init(centerSpaceTime, "work", beamVehicle = Some(vehicle2), triggerId = 49238)
        val response2 = chargingNetwork.processParkingInquiry(secondInquiry)
        chargingNetwork.processParkingInquiry(secondInquiry)
        assert(
          response2.stall.chargingPointType.isEmpty,
          "it should not get an Ultra Fast charging point stall"
        )
      }
    }
  }

  describe("Parking/Charging point selection") {

    val baseConfig = system.settings.config
    val beamConfig = BeamConfig(baseConfig)

    it("should happen with the expected probability in respect to the WalkingEgressCost") {

      val (tazTreeMap, _, searchRadius, tazSpacing) = createTazGrid()
      // increases valueOfTime to amplify the differences between parking options
      val valueOfTime = beamConfig.beam.agentsim.agents.modalBehaviors.defaultValueOfTime * 10

      val destinationSpaceTime = centerSpaceTime
      val originSpaceTime =
        SpaceTime(centerSpaceTime.loc.getX - tazSpacing / 2, centerSpaceTime.loc.getY - tazSpacing / 2, 0)

      val vehicle = createBEV(originSpaceTime)

      var sumExpUtility = 0.0
      val expectedProbability = new mutable.ListMap[Id[ParkingZoneId], Double]()
      val parkingOptions = new ListBuffer[String]()
      parkingOptions += "taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor,parkingZoneId"
      for (taz <- tazTreeMap.idToTAZMapping) {
        val tazCoord = taz._2.coord
        val currentId = taz._1.toString
        val distance = GeoUtils.distUTMInMeters(tazCoord, destinationSpaceTime.loc)

        parkingOptions += s"$currentId,Public,FlatFee,Level1(2.3|AC),9999,0,,$currentId"

        val expUtility =
          math.exp(
            (distance / ZonalParkingManager.AveragePersonWalkingSpeed / ZonalParkingManager.HourInSeconds) *
            valueOfTime * beamConfig.beam.agentsim.agents.parking.multinomialLogit.params.distanceMultiplier
          )
        sumExpUtility += expUtility
        expectedProbability += Id.create[ParkingZoneId](s"$currentId", classOf[ParkingZoneId]) -> expUtility
      }
      expectedProbability.foreach(zoneId_prob => expectedProbability(zoneId_prob._1) /= sumExpUtility)

      val parkingInquiry = ParkingInquiry.init(
        destinationSpaceTime,
        "move",
        beamVehicle = Some(vehicle),
        triggerId = 49238,
        valueOfTime = valueOfTime
      )
      val configWithSeedFunction: Int => String =
        (seed: Int) => s"""|matsim.modules.global.randomSeed = $seed
              |beam.agentsim.agents.parking.minSearchRadius = $searchRadius
              |beam.agentsim.agents.parking.maxSearchRadius = $searchRadius""".stripMargin

      runParkingSelectionTest(
        baseConfig,
        configWithSeedFunction,
        tazTreeMap,
        parkingOptions,
        parkingInquiry,
        expectedProbability
      )

    }

    it("should happen with the expected probability in respect to the ParkingTicketCost") {

      val (tazTreeMap, _, searchRadius, tazSpacing) = createTazGrid(horizontalCount = 1, verticalCount = 1)

      val nStalls: Int = 10
      val minCostInCents: Int = 1000
      val maxCostInCents: Int = 10000

      val destinationSpaceTime = centerSpaceTime
      val originSpaceTime =
        SpaceTime(centerSpaceTime.loc.getX - tazSpacing / 2, centerSpaceTime.loc.getY - tazSpacing / 2, 0)

      val vehicle = createBEV(originSpaceTime)

      val tazId = tazTreeMap.idToTAZMapping.head._1.toString
      var sumExpUtility = 0.0
      val expectedProbability = new mutable.ListMap[Id[ParkingZoneId], Double]()
      val parkingOptions = new ListBuffer[String]()
      parkingOptions += "taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor,parkingZoneId"
      for (stallIndex <- 0 until nStalls) {
        val feeInCents = math
          .ceil(
            (maxCostInCents - minCostInCents) * (math
              .exp(stallIndex / (nStalls - 1.0)) - 1.0) / (math.E - 1.0) + minCostInCents
          )
          .toInt
        parkingOptions += s"$tazId,Public,FlatFee,Level1(2.3|AC),9999,$feeInCents,,$stallIndex"

        val expUtility =
          math.exp(
            feeInCents / 100.0 * beamConfig.beam.agentsim.agents.parking.multinomialLogit.params.parkingPriceMultiplier
          )
        sumExpUtility += expUtility
        expectedProbability += Id.create[ParkingZoneId](s"$stallIndex", classOf[ParkingZoneId]) -> expUtility
      }
      expectedProbability.foreach(zoneId_prob => expectedProbability(zoneId_prob._1) /= sumExpUtility)

      val parkingInquiry = ParkingInquiry.init(
        destinationSpaceTime,
        "move",
        beamVehicle = Some(vehicle),
        triggerId = 49238
      )
      val configWithSeedFunction: Int => String =
        (seed: Int) => s"""|matsim.modules.global.randomSeed = $seed
              |beam.agentsim.agents.parking.minSearchRadius = $searchRadius
              |beam.agentsim.agents.parking.maxSearchRadius = $searchRadius""".stripMargin

      runParkingSelectionTest(
        baseConfig,
        configWithSeedFunction,
        tazTreeMap,
        parkingOptions,
        parkingInquiry,
        expectedProbability
      )

    }

    it("should happen with the expected probability in respect to the EnrouteDetourCost") {

      val (tazTreeMap, boundingBox, searchRadius, _) = createTazGrid()
      val valueOfTime = beamConfig.beam.agentsim.agents.modalBehaviors.defaultValueOfTime

      val destinationSpaceTime = SpaceTime(boundingBox.getMinX, boundingBox.centre().y, 0)
      val originSpaceTime = SpaceTime(boundingBox.getMaxX, boundingBox.centre().y, 0)

      val vehicle = createBEV(originSpaceTime)

      val tazInSearchEllipse = tazTreeMap.tazQuadTree
        .getElliptical(
          originSpaceTime.loc.getX,
          originSpaceTime.loc.getY,
          destinationSpaceTime.loc.getX,
          destinationSpaceTime.loc.getY,
          GeoUtils.distUTMInMeters(originSpaceTime.loc, destinationSpaceTime.loc) * 1.01
        )
        .asScala
        .toList
        .map(_.tazId)

      var sumExpUtility = 0.0
      val expectedProbability = new mutable.ListMap[Id[ParkingZoneId], Double]()
      val parkingOptions = new ListBuffer[String]()
      parkingOptions += "taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor,parkingZoneId"
      for (taz <- tazTreeMap.idToTAZMapping) {
        val tazCoord = taz._2.coord
        val currentId = taz._1.toString
        parkingOptions += s"$currentId,Public,FlatFee,UltraFast(50|DC),9999,0,,$currentId"

        val travelTime1 = SkimsUtils.distanceAndTime(beam.router.Modes.BeamMode.CAR, originSpaceTime.loc, tazCoord)._2
        val travelTime2 =
          SkimsUtils.distanceAndTime(beam.router.Modes.BeamMode.CAR, tazCoord, destinationSpaceTime.loc)._2

        val expUtility = if (tazInSearchEllipse.contains(taz._1)) {
          math.exp(
            (travelTime1 + travelTime2) * valueOfTime / ZonalParkingManager.HourInSeconds *
            beamConfig.beam.agentsim.agents.parking.multinomialLogit.params.enrouteDetourMultiplier
          )
        } else {
          0.0
        }
        sumExpUtility += expUtility
        expectedProbability += Id.create[ParkingZoneId](s"$currentId", classOf[ParkingZoneId]) -> expUtility
      }
      expectedProbability.foreach(zoneId_prob => expectedProbability(zoneId_prob._1) /= sumExpUtility)

      val parkingInquiry = ParkingInquiry.init(
        destinationSpaceTime,
        "enroute",
        beamVehicle = Some(vehicle),
        triggerId = 49238,
        valueOfTime = valueOfTime,
        searchMode = ParkingSearchMode.EnRouteCharging,
        originUtm = Some(originSpaceTime)
      )
      val configWithSeedFunction: Int => String =
        (seed: Int) => s"""|matsim.modules.global.randomSeed = $seed
              |beam.agentsim.agents.parking.minSearchRadius = $searchRadius
              |beam.agentsim.agents.parking.maxSearchRadius = $searchRadius""".stripMargin

      runParkingSelectionTest(
        baseConfig,
        configWithSeedFunction,
        tazTreeMap,
        parkingOptions,
        parkingInquiry,
        expectedProbability
      )

    }
  }

  def runParkingSelectionTest(
    baseConfig: Config,
    configWithSeedFunction: Int => String,
    tazTreeMap: TAZTreeMap,
    parkingOptions: ListBuffer[String],
    parkingInquiry: ParkingInquiry,
    expectedProbability: mutable.ListMap[Id[ParkingZoneId], Double],
    maxDeviation: Double = 15e-2,
    iterations: Int = 1000
  ): Unit = {

    val rand = new Random()
    val observedProbability = new mutable.ListMap[Id[ParkingZoneId], Double]()
    for (iteration <- 0 until iterations) {
      val conf = ConfigFactory
        .parseString(configWithSeedFunction(rand.nextInt()))
        .withFallback(baseConfig)
        .resolve()
      // a new charging network is created to change ChargingFunctions.seed
      // otherwise, every single response will be the same
      val chargingNetwork = ChargingNetworkSpec.mockChargingNetwork(
        BeamConfig(conf),
        tazTreeMap,
        geo,
        parkingOptions.toIterator,
        boundingBox
      )
      val response = chargingNetwork.processParkingInquiry(parkingInquiry)
      observedProbability.get(response.stall.parkingZoneId) match {
        case Some(count) => observedProbability.update(response.stall.parkingZoneId, count + 1)
        case None        => observedProbability += response.stall.parkingZoneId -> 1
      }
    }
    observedProbability.foreach(stall_prob => observedProbability(stall_prob._1) /= iterations)

    val relativeError = math.sqrt( // 2-norm of the difference on observed and expected probabilities
      expectedProbability
        .map(expected => math.pow(expected._2 - observedProbability.getOrElse(expected._1, 0.0), 2.0))
        .sum
    ) / math.sqrt(
      expectedProbability // 2-norm of the expected probabilities
        .map(expected => math.pow(expected._2, 2.0))
        .sum
    )

    assert(
      relativeError < maxDeviation,
      s"The difference between expected and observed probabilities for choosing parking stalls was above the maximum allowed"
    )
  }

  def createTazGrid(
    startingTazId: Int = 1,
    tazSpacing: Double = 1000.0,
    tazArea: Double = 0.0, // ensures that the parking zone is created at exactly the center when tazArea = 0.0
    horizontalCount: Int = 5,
    verticalCount: Int = 5
  ): (TAZTreeMap, Envelope, Double, Double) = {

    val boundingBox = new Envelope(
      coordCenterOfUTM.getX - (horizontalCount / 2 + 1) * tazSpacing,
      coordCenterOfUTM.getX + (horizontalCount / 2 + 1) * tazSpacing,
      coordCenterOfUTM.getY - (verticalCount / 2 + 1) * tazSpacing,
      coordCenterOfUTM.getY + (verticalCount / 2 + 1) * tazSpacing
    )
    val searchRadius = GeoUtils.distUTMInMeters(
      new Coord(boundingBox.getMinX, boundingBox.getMinY),
      new Coord(boundingBox.getMaxX, boundingBox.getMaxY)
    )

    val tazCenters = new ListBuffer[(Coord, Double)]()
    for (i <- 0 until horizontalCount) {
      for (j <- 0 until verticalCount) {
        val tazCoord = new Coord(
          (i - horizontalCount / 2) * tazSpacing + coordCenterOfUTM.getX,
          (j - verticalCount / 2) * tazSpacing + coordCenterOfUTM.getY
        )
        tazCenters += tazCoord -> tazArea
      }
    }

    val tazTreeMap = ZonalParkingManagerSpec
      .mockTazTreeMap(
        tazCenters.toList,
        startAtId = startingTazId,
        boundingBox.getMinX,
        boundingBox.getMinY,
        boundingBox.getMaxX,
        boundingBox.getMaxY
      )
      .get
    (tazTreeMap, boundingBox, searchRadius, tazSpacing)
  }

  def createBEV(originSpaceTime: SpaceTime, joulesPerMeter: Double = 0.0, id: String = "car-00"): BeamVehicle = {
    val vehicleType = beamScenario.vehicleTypes(Id.create("BEV", classOf[BeamVehicleType]))
    val vehicle = new BeamVehicle(
      id = Id.createVehicleId(id),
      powerTrain = new Powertrain(joulesPerMeter),
      beamVehicleType = vehicleType
    )
    vehicle.spaceTime = originSpaceTime
    vehicle
  }
}

object ChargingNetworkSpec {

  def mockChargingNetwork(
    beamConfig: BeamConfig,
    tazTreeMap: TAZTreeMap,
    geo: GeoUtils,
    parkingDescription: Iterator[String],
    boundingBox: Envelope
  ): ChargingNetwork = {
    ChargingNetwork(
      parkingDescription,
      tazTreeMap.tazQuadTree,
      tazTreeMap.idToTAZMapping,
      boundingBox,
      beamConfig,
      None,
      geo.distUTMInMeters(_, _)
    )
  }
}
