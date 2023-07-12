package beam.agentsim.agents

import beam.agentsim.events.PathTraversalEvent
import beam.sim.BeamHelper
import beam.utils.EventReader
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.vehicles.Vehicle
import org.scalatest.AppendedClues.convertToClueful
import org.scalatest.BeforeAndAfterAllConfigMap
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

import scala.util.Random
import scala.util.matching.Regex

class ElectricVehicleChargingBehaviorTest
    extends AnyFlatSpec
    with Matchers
    with BeamHelper
    with BeforeAndAfterAllConfigMap {

  private val filesPath = s"""$${beam.inputDirectory}"/../../test-resources/ElectricVehicleChargingBehaviorTestData"""

  private val seed = Random.nextInt()

  private val baseConfig: String =
    s"""
       |matsim.modules.global.randomSeed = $seed
       |beam.outputs.events.fileOutputFormats = xml
       |beam.physsim.skipPhysSim = true
       |beam.agentsim.lastIteration = 0
       |beam.agentsim.agents.vehicles.sharedFleets = []
       |beam.agentsim.taz.filePath=$filesPath/taz-centers.csv"
        """

  private val beamvilleConfig = testConfig("test/input/beamville/beam.conf")

  private val personalConfig = ConfigFactory
    .parseString(
      s"""$baseConfig
         |beam.agentsim.agents.vehicles.enroute.noRefuelThresholdOffsetInMeters = 0.0
         |beam.agentsim.agents.plans {
         |  inputPlansFilePath = $filesPath/population.xml"
         |  inputPersonAttributesFilePath = $filesPath/populationAttributes.xml"
         |}
         |beam.agentsim.agents.households {
         |  inputFilePath = $filesPath/households.xml"
         |  inputHouseholdAttributesFilePath = $filesPath/householdAttributes.xml"
         |}
         |beam.agentsim.agents.vehicles.vehiclesFilePath = $filesPath/vehicles.csv"
         |beam.agentsim.agents.rideHail.managers = [
         |  {
         |    initialization.procedural.fractionOfInitialVehicleFleet = 0
         |    initialization.procedural.vehicleTypeId = "beamVilleCar"
         |  }
         |]
        """.stripMargin
    )
    .withFallback(beamvilleConfig)
    .resolve()

  private val rideHailConfig =
    s"""$baseConfig
       |beam.agentsim.agents.rideHail.managers = [
       |  {
       |    iterationStats.timeBinSizeInSec = 3600
       |    defaultCostPerMile = 1.25
       |    defaultCostPerMinute = 0.75
       |    rideHailManager.radiusInMeters = 50000
       |    # allocationManager(DEFAULT_MANAGER | EV_MANAGER | POOLING_ALONSO_MORA)
       |    allocationManager.name = "POOLING_ALONSO_MORA"
       |    allocationManager.requestBufferTimeoutInSeconds = 200
       |    allocationManager.maxWaitingTimeInSec = 18000
       |    allocationManager.maxExcessRideTime = 0.5 # up to +50%
       |    allocationManager.matchingAlgorithm = "ALONSO_MORA_MATCHING_WITH_ASYNC_GREEDY_ASSIGNMENT"
       |    allocationManager.alonsoMora.maxRequestsPerVehicle = 5
       |    repositioningManager.name = "DEMAND_FOLLOWING_REPOSITIONING_MANAGER"
       |    repositioningManager.timeout = 300
       |    # DEMAND_FOLLOWING_REPOSITIONING_MANAGER
       |    repositioningManager.demandFollowingRepositioningManager.sensitivityOfRepositioningToDemand = 1
       |    repositioningManager.demandFollowingRepositioningManager.numberOfClustersForDemand = 30
       |    # REPOSITIONING_LOW_WAITING_TIMES
       |    allocationManager.repositionLowWaitingTimes.percentageOfVehiclesToReposition = 0.0
       |    allocationManager.repositionLowWaitingTimes.repositionCircleRadiusInMeters = 100
       |    allocationManager.repositionLowWaitingTimes.timeWindowSizeInSecForDecidingAboutRepositioning = 12000
       |    allocationManager.repositionLowWaitingTimes.allowIncreasingRadiusIfDemandInRadiusLow = true
       |    allocationManager.repositionLowWaitingTimes.minDemandPercentageInRadius = 0.1
       |    allocationManager.repositionLowWaitingTimes.minimumNumberOfIdlingVehiclesThresholdForRepositioning = 1000
       |    allocationManager.repositionLowWaitingTimes.repositioningMethod = "TOP_SCORES"
       |    allocationManager.repositionLowWaitingTimes.keepMaxTopNScores = 5
       |    allocationManager.repositionLowWaitingTimes.minScoreThresholdForRepositioning = 100000000.0
       |    allocationManager.repositionLowWaitingTimes.distanceWeight = 0.01
       |    allocationManager.repositionLowWaitingTimes.waitingTimeWeight = 4.0
       |    allocationManager.repositionLowWaitingTimes.demandWeight = 4.0
       |    allocationManager.repositionLowWaitingTimes.produceDebugImages = true
       |    initialization.filePath = $filesPath/RIDE_HAIL_FLEET_FILE"
       |    initialization.initType="FILE"
       |    initialization.parking.filePath=$filesPath/taz-parking-empty.csv"
       |  }
       |]
       |beam.agentsim.agents.plans {
       |  inputPlansFilePath = $filesPath/populationRideHail.xml"
       |  inputPersonAttributesFilePath = $filesPath/populationAttributes.xml"
       |}
       |beam.agentsim.agents.households {
       |  inputFilePath = $filesPath/householdsNoVehicles.xml"
       |  inputHouseholdAttributesFilePath = $filesPath/householdAttributes.xml"
       |}
       |beam.agentsim.agents.vehicles.vehiclesFilePath = $filesPath/vehicles-empty.csv"
       |beam.agentsim.agents.parking.minSearchRadius = 8000
        """.stripMargin

  /*

    TAZ MAP:
                     -------- 20, 22, 28 -------- 21, 23, 29 --------
                     |      167692.88,2213.6    168807.15,2213.6    |
             home(8) -------- 10, 12, 18 -------- 11, 13, 19 -------- work(9)
    166021.46,1106.8 |      167692.88,1106.8    168807.15,1106.8    | 169921.40,1106.8
                     -------- 30, 32, 38 -------- 31, 33, 39 --------
                            167692.88,5.53      168807.15,5.53

    TAZs are used to identify different parking zones by ensuring there is at most one parking zone on each TAZ.
    Parking zone coordinates are defined to be exactly on the center of each TAZ by setting TAZs area to zero.

   */

  "Electric vehicles" should "charge at their destination and should not run out of energy" in {
    val config = ConfigFactory
      .parseString(
        s"""
           |beam.agentsim.taz.parkingFilePath = $filesPath/taz-parking-destination-only.csv"
           |beam.agentsim.agents.vehicles.vehicleTypesFilePath = $filesPath/vehicleTypes-low-capacity.csv"
          """.stripMargin
      )
      .withFallback(personalConfig)
      .resolve()

    val (matsimConfig, _, _) = runBeamWithConfig(config)

    val events = EventReader.fromXmlFile(
      EventReader.getEventsFilePath(matsimConfig, "events", "xml").getAbsolutePath
    )

    val homeTaz = "8"
    val workTaz = "9"
    val unsuitableChargersTAZs = List("18", "19", "28", "29", "38", "39")
    val fastChargersTAZs = List("10", "11", "20", "21", "30", "31")

    val homePluginEvents = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent")),
      ("parkingTaz", a => a.equals(homeTaz))
    )
    val workPluginEvents = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent")),
      ("parkingTaz", a => a.equals(workTaz))
    )
    val unsuitablePluginEvents = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent")),
      ("parkingTaz", (a: String) => unsuitableChargersTAZs.contains(a))
    )
    val fastChargerPluginEvents = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent")),
      ("parkingTaz", (a: String) => fastChargersTAZs.contains(a))
    )

    val vehicleIds = findAllElectricVehicles(events).map(id => id.toString)
    vehicleIds.size shouldEqual 50 withClue ", expecting 50 electric vehicles."

    unsuitablePluginEvents.size shouldEqual 0 withClue
    ", vehicles should not be enrouting, specially to chargers without sufficient power rating, neither should they " +
    "home, work or long park on fast chargers."

    fastChargerPluginEvents.size shouldEqual 0 withClue
    ", vehicles should not be enrouting, neither should they home, work or long park on fast chargers."

    homePluginEvents.size shouldEqual 100 withClue ", expecting 2 home plug-in events for each of the 50 vehicles."
    workPluginEvents.size shouldEqual 100 withClue ", expecting 2 work plug-in events for each of the 50 vehicles."

    val negativeFuelPTEs =
      filterEvents(
        events,
        ("type", a => a.equals("PathTraversal")),
        ("vehicle", (a: String) => vehicleIds.contains(a)),
        ("primaryFuelLevel", (a: String) => a.toDouble < 0.0)
      )

    negativeFuelPTEs.size shouldEqual 0 withClue ", vehicles should not be running out of energy."
  }

  "Electric vehicles" should "always enroute when there is not enough energy to reach their destination choosing smaller EnrouteDetourCost." in {
    val config = ConfigFactory
      .parseString(
        s"""
           |beam.agentsim.taz.parkingFilePath = $filesPath/taz-parking-enroute-only-free.csv"
           |beam.agentsim.agents.vehicles.vehicleTypesFilePath = $filesPath/vehicleTypes-very-low-capacity.csv"
        """.stripMargin
      )
      .withFallback(personalConfig)
      .resolve()

    val (matsimConfig, _, _) = runBeamWithConfig(config)

    val events = EventReader.fromXmlFile(
      EventReader.getEventsFilePath(matsimConfig, "events", "xml").getAbsolutePath
    )

    val centerTAZs = List("10", "11")
    val borderTAZs = List("20", "21", "30", "31")

    val centerPluginEvents = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent")),
      ("parkingTaz", (a: String) => centerTAZs.contains(a))
    )
    val centerEnRouteSessionEvents = filterEvents(
      events,
      ("type", a => a.equals("RefuelSessionEvent")),
      ("parkingTaz", (a: String) => centerTAZs.contains(a)),
      ("actType", a => a.startsWith("EnRoute-"))
    )
    val borderPluginEvents = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent")),
      ("parkingTaz", (a: String) => borderTAZs.contains(a))
    )
    val borderEnRouteSessionEvents = filterEvents(
      events,
      ("type", a => a.equals("RefuelSessionEvent")),
      ("parkingTaz", (a: String) => borderTAZs.contains(a)),
      ("actType", a => a.startsWith("EnRoute-"))
    )
    // PHEV vehicles are supposed to have a level2 charging capability
    // therefore they don't have capability to charge in fast charging stations
    val unsuitableRefuelSessionEvents1 = filterEvents(
      events,
      ("type", a => a.equals("RefuelSessionEvent")),
      ("vehicleType", (a: String) => a.equals("PHEV"))
    )
    unsuitableRefuelSessionEvents1.size shouldEqual 0 withClue
    ", ride hail vehicles of PHEV type should not be charging in fast chargers"

    val vehicleIds = findAllElectricVehicles(events).map(id => id.toString)
    vehicleIds.size shouldEqual 50 withClue ", expecting 50 electric vehicles."
    centerEnRouteSessionEvents.size should be > borderEnRouteSessionEvents.size withClue
    ", agents more likely to enroute charge in center TAZs than in border ones."
    centerPluginEvents.size should be > borderPluginEvents.size withClue
    ", agents should prefer center chargers for enrouting (smaller EnrouteDetourCost)."
  }

  "Electric vehicles" should "always enroute when there is not enough energy to reach their destination choosing smaller ParkingTicketCost." in {
    val config = ConfigFactory
      .parseString(
        s"""
           |beam.agentsim.taz.parkingFilePath = $filesPath/taz-parking-enroute-only-mixed-prices.csv"
           |beam.agentsim.agents.vehicles.vehicleTypesFilePath = $filesPath/vehicleTypes-very-low-capacity.csv"
        """.stripMargin
      )
      .withFallback(personalConfig)
      .resolve()

    val (matsimConfig, _, _) = runBeamWithConfig(config)

    val events = EventReader.fromXmlFile(
      EventReader.getEventsFilePath(matsimConfig, "events", "xml").getAbsolutePath
    )

    val freeChargersTAZs = List("20", "21", "30", "31")
    val expensiveChargersTAZs = List("22", "23", "32", "33")

    val freePluginEvents = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent")),
      ("parkingTaz", (a: String) => freeChargersTAZs.contains(a))
    )
    val freeEnRouteSessionEvents = filterEvents(
      events,
      ("type", a => a.equals("RefuelSessionEvent")),
      ("parkingTaz", (a: String) => freeChargersTAZs.contains(a)),
      ("actType", a => a.startsWith("EnRoute-"))
    )
    val expensivePluginEvents = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent")),
      ("parkingTaz", (a: String) => expensiveChargersTAZs.contains(a))
    )
    val expensiveEnRouteSessionEvents = filterEvents(
      events,
      ("type", a => a.equals("RefuelSessionEvent")),
      ("parkingTaz", (a: String) => expensiveChargersTAZs.contains(a)),
      ("actType", a => a.startsWith("EnRoute-"))
    )
    // PHEV vehicles are supposed to have a level2 charging capability
    // therefore they don't have capability to charge in fast charging stations
    val unsuitableRefuelSessionEvents1 = filterEvents(
      events,
      ("type", a => a.equals("RefuelSessionEvent")),
      ("vehicleType", (a: String) => a.equals("PHEV"))
    )
    unsuitableRefuelSessionEvents1.size shouldEqual 0 withClue
    ", ride hail vehicles of PHEV type should not be charging in fast chargers"

    val vehicleIds = findAllElectricVehicles(events).map(id => id.toString)
    vehicleIds.size shouldEqual 50 withClue ", expecting 50 electric vehicles."

    freeEnRouteSessionEvents.size should be > expensiveEnRouteSessionEvents.size withClue
    ", agents, at least 10 times, more likely to enroute charge at cheaper chargers than in expensive ones."

    freePluginEvents.size should be > expensivePluginEvents.size withClue
    ", agents should prefer cheaper chargers for enrouting (smaller ParkingTicketCost)."
  }

  "Ride Hail Electric vehicles" should "only recharge at suitable charging stations." in {
    val config = ConfigFactory
      .parseString(
        s"""$rideHailConfig
           |beam.agentsim.taz.parkingFilePath = $filesPath/taz-parking-ride-hail.csv"
           |beam.agentsim.agents.vehicles.vehicleTypesFilePath = $filesPath/vehicleTypes-low-capacity.csv"
           |beam.agentsim.agents.modalBehaviors.multinomialLogit.params.ride_hail_intercept = 10000000
           |beam.agentsim.agents.rideHail.human.refuelRequiredThresholdInMeters = 1000.0
           |beam.agentsim.agents.rideHail.human.noRefuelThresholdInMeters = 1300.0
           |beam.agentsim.agents.rideHail.cav.refuelRequiredThresholdInMeters = 1000.0
           |beam.agentsim.agents.rideHail.cav.noRefuelThresholdInMeters = 1300.0
           |beam.agentsim.agents.rideHail.rangeBufferForDispatchInMeters = 0
           |beam.agentsim.tuning.rideHailPrice = 0.0
      """.stripMargin.replace("RIDE_HAIL_FLEET_FILE", "rideHailFleet.csv")
      )
      .withFallback(beamvilleConfig)
      .resolve()

    val (matsimConfig, _, _) = runBeamWithConfig(config)

    val events = EventReader.fromXmlFile(
      EventReader.getEventsFilePath(matsimConfig, "events", "xml").getAbsolutePath
    )

    val cavRegex: Regex = """^rideHailVehicle-\d+-L5@GlobalRHM\Z""".r
    val humanRegex: Regex = """^rideHailVehicle-\d+@GlobalRHM\Z""".r

    val cavReservedTAZs = List("12", "13", "22", "23", "32", "33")
    val humanReservedTAZs = List("10", "11", "20", "21", "30", "31")

    val vehicleIds = findAllElectricVehicles(events).map(id => id.toString)
    vehicleIds.size shouldEqual 50 withClue ", expecting 50 electric vehicles."

    // PHEV vehicles are supposed to have a level2 charging capability
    // therefore they don't have capability to charge in fast charging stations
    val unsuitableRefuelSessionEvents1 = filterEvents(
      events,
      ("type", a => a.equals("RefuelSessionEvent")),
      ("vehicleType", (a: String) => a.equals("PHEV"))
    )
    unsuitableRefuelSessionEvents1.size shouldEqual 0 withClue
    ", ride hail vehicles of PHEV type should not be charging in fast chargers"

    val unsuitableRefuelSessionEvents2 = filterEvents(
      events,
      ("type", a => a.equals("RefuelSessionEvent")),
      ("stall", (a: String) => a.contains("Level1")),
      ("tick", (a: String) => a.toDouble >= 8 * 3600.0 && a.toDouble <= 20 * 3600.0)
    )
    unsuitableRefuelSessionEvents2.size shouldEqual 0 withClue
    ", ride hail vehicles should not be charging in slow chargers during shift hours. "

    val cavReservedPluginEvents = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent")),
      ("parkingTaz", (a: String) => cavReservedTAZs.contains(a))
    )
    val humanReservedPluginEvents = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent")),
      ("parkingTaz", (a: String) => humanReservedTAZs.contains(a))
    )

    cavReservedPluginEvents.size shouldEqual filterEvents(
      cavReservedPluginEvents,
      ("vehicle", a => cavRegex.findFirstMatchIn(a).isDefined)
    ).size withClue
    ", only L5 automated vehicles should be charging on ride hail reserved parking zones."

    humanReservedPluginEvents.size shouldEqual filterEvents(
      humanReservedPluginEvents,
      ("vehicle", a => humanRegex.findFirstMatchIn(a).isDefined)
    ).size withClue
    ", L5 automated vehicles should not be charging on unreserved parking zones."

    val distinctVehiclesCharged = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent"))
    ).map(_.getAttributes.get("vehicle")).distinct

    distinctVehiclesCharged.size should be >= 45 withClue
    ", expecting that almost every vehicle recharges at least once."

    val rideHailArrivalEvents = filterEvents(
      events,
      ("type", a => a.equals("arrival")),
      ("legMode", a => a.equals("ride_hail"))
    )

    rideHailArrivalEvents.size should be >= 195 withClue
    ", expecting most of the 4 legs for each of the 50 people to be ride hail legs."
  }

  "Ride Hail Electric vehicles" should "pick chargers choosing smaller DrivingTimeCost." in {
    // this config is only interested on the first charging plugin event when,
    // vehicles are at known coordinates, population plans are set to walk to not interfere with ride hail.
    val config = ConfigFactory
      .parseString(
        s"""$rideHailConfig
           |beam.agentsim.agents.plans.inputPlansFilePath = $filesPath/populationWalk.xml"
           |beam.agentsim.taz.parkingFilePath = $filesPath/taz-parking-ride-hail-driving-time-cost.csv"
           |# 15 Km range
           |beam.agentsim.agents.vehicles.vehicleTypesFilePath = $filesPath/vehicleTypes-low-capacity.csv"
           |beam.agentsim.agents.rideHail.charging.multinomialLogit.params.drivingTimeMultiplier = -0.01666667 # default
           |# initial SoC is 0.7 or 10.5 Km, vehicles should immediately pick a charging station
           |beam.agentsim.agents.modalBehaviors.multinomialLogit.params.ride_hail_intercept = 0
           |beam.agentsim.agents.rideHail.human.refuelRequiredThresholdInMeters = 11000.0
           |beam.agentsim.agents.rideHail.human.noRefuelThresholdInMeters = 12000.0
           |beam.agentsim.agents.rideHail.cav.refuelRequiredThresholdInMeters = 11000.0
           |beam.agentsim.agents.rideHail.cav.noRefuelThresholdInMeters = 12000.0
           |beam.agentsim.agents.rideHail.rangeBufferForDispatchInMeters = 0
      """.stripMargin.replace("RIDE_HAIL_FLEET_FILE", "rideHailFleet0.7soc.csv")
      )
      .withFallback(beamvilleConfig)
      .resolve()

    val (matsimConfig, _, _) = runBeamWithConfig(config)

    val events = EventReader.fromXmlFile(
      EventReader.getEventsFilePath(matsimConfig, "events", "xml").getAbsolutePath
    )

    //val closestTAZsHuman = List("10")
    val closestTAZsCAV = List("12")
    //val furtherTAZsHuman = List("11", "21", "31")
    val furtherTAZsCAV = List("13", "23", "33")

    val vehicleIds = findAllElectricVehicles(events).map(id => id._1.toString)
    vehicleIds.size shouldEqual 50 withClue ", expecting 50 electric vehicles."

    val vehiclesCharged = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent"))
    ).map(e => e.getAttributes.get("vehicle")).distinct

    vehiclesCharged.size should be > 0 withClue ", some vehicles should had charged at least once."

    // PHEV vehicles are supposed to have a level2 charging capability
    // therefore they don't have capability to charge in fast charging stations
    val unsuitableRefuelSessionEvents1 = filterEvents(
      events,
      ("type", a => a.equals("RefuelSessionEvent")),
      ("vehicleType", (a: String) => a.equals("PHEV"))
    )
    unsuitableRefuelSessionEvents1.size shouldEqual 0 withClue
    ", ride hail vehicles of PHEV type should not be charging in fast chargers"

    val firstPluginEvents = vehicleIds.foldLeft(List[Event]()) { (plugIns, vid) =>
      val firstPlugInMaybe = filterEvents(
        events,
        ("type", a => a.equals("ChargingPlugInEvent")),
        ("vehicle", a => a.equals(vid))
      ).headOption
      firstPlugInMaybe.map(plugIns :+ _).getOrElse(plugIns)
    }

    val pluginCountByTAZ = firstPluginEvents.map(_.getAttributes.get("parkingTaz")).groupBy(identity).mapValues(_.size)

    val closeTazPluginCAVCount = closestTAZsCAV.foldLeft(0) { (count, taz) =>
      count + pluginCountByTAZ.getOrElse(taz, 0)
    }
    val farTazPluginCAVCount = furtherTAZsCAV.foldLeft(0) { (count, taz) =>
      count + pluginCountByTAZ.getOrElse(taz, 0)
    }

    closeTazPluginCAVCount should be > farTazPluginCAVCount withClue
    ", vehicles should be picking the closest charger more often than the farther ones."

    // currently,there is no parameter to get influenced by the distance for human ride hail
    // this parameter should exist, when the search is updated this should be turned into active code.
    //val closeTazPluginHumanCount = closestTAZsHuman.foldLeft(0) { (count, taz) =>
    //  count + pluginCountByTAZ.getOrElse(taz, 0)
    //}
    //val farTazPluginHumanCount = furtherTAZsHuman.foldLeft(0) { (count, taz) =>
    //  count + pluginCountByTAZ.getOrElse(taz, 0)
    //}
    //closeTazPluginHumanCount should be > farTazPluginHumanCount withClue
    //", vehicles should be picking the closest charger more often than the farther ones."
  }

  "Ride Hail Electric vehicles" should "pick chargers choosing smaller ChargingTimeCost." in {
    val config = ConfigFactory
      .parseString(
        s"""$rideHailConfig
           |beam.agentsim.taz.parkingFilePath = $filesPath/taz-parking-ride-hail-charging-time-cost.csv"
           |# 5 Km range
           |beam.agentsim.agents.vehicles.vehicleTypesFilePath =  $filesPath/vehicleTypes-high-capacity-low-range.csv"
           |beam.agentsim.agents.modalBehaviors.multinomialLogit.params.ride_hail_intercept = 0
           |beam.agentsim.agents.rideHail.human.refuelRequiredThresholdInMeters = 1000.0
           |beam.agentsim.agents.rideHail.human.noRefuelThresholdInMeters = 1500.0
           |beam.agentsim.agents.rideHail.cav.refuelRequiredThresholdInMeters = 1000.0
           |beam.agentsim.agents.rideHail.cav.noRefuelThresholdInMeters = 1500.0
           |beam.agentsim.agents.rideHail.rangeBufferForDispatchInMeters = 0
      """.stripMargin.replace("RIDE_HAIL_FLEET_FILE", "rideHailFleet0soc.csv")
      )
      .withFallback(beamvilleConfig)
      .resolve()

    val (matsimConfig, _, _) = runBeamWithConfig(config)

    val events = EventReader.fromXmlFile(
      EventReader.getEventsFilePath(matsimConfig, "events", "xml").getAbsolutePath
    )

    val ultrafastTAZs = List("10", "11", "20", "21", "30", "31")
    val fastTAZs = List("12", "18", "13", "19", "22", "28", "23", "29", "32", "38", "33", "39")

    val vehicleIds = findAllElectricVehicles(events).map(id => id.toString)
    vehicleIds.size shouldEqual 50 withClue ", expecting 50 electric vehicles."

    val pluginEvents = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent"))
    )

    val vehiclesCharged = pluginEvents.map(e => e.getAttributes.get("vehicle")).distinct

    vehiclesCharged.size shouldEqual 50 withClue ", every single L5 automated vehicle should had charged at least once."

    val pluginCountByTAZ = pluginEvents.map(_.getAttributes.get("parkingTaz")).groupBy(identity).mapValues(_.size)

    val ultrafastTazPluginCAVCount = ultrafastTAZs.foldLeft(0) { (count, taz) =>
      count + pluginCountByTAZ.getOrElse(taz, 0)
    }
    val fastTazPluginCAVCount = fastTAZs.foldLeft(0) { (count, taz) =>
      count + pluginCountByTAZ.getOrElse(taz, 0)
    }

    ultrafastTazPluginCAVCount should be > fastTazPluginCAVCount withClue
    ", vehicles should be picking the faster chargers (more power output) more often than the slower ones."
  }

  def filterEvents(events: IndexedSeq[Event], filters: (String, String => Boolean)*): IndexedSeq[Event] = {
    events.filter(event =>
      filters.forall(filter =>
        event.getAttributes.containsKey(filter._1) && filter._2(event.getAttributes.get(filter._1))
      )
    )
  }

  def findAllElectricVehicles(events: IndexedSeq[Event]): IndexedSeq[(Id[Vehicle], String)] = {
    events.collect {
      case pte: PathTraversalEvent if pte.primaryFuelType == "Electricity" => (pte.vehicleId, pte.vehicleType)
    }.distinct
  }
}
