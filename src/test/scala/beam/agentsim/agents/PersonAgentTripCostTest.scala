package beam.agentsim.agents

import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.vehicles.{BeamVehicleType, FuelType}
import beam.agentsim.events.PathTraversalEvent
import beam.sim.BeamHelper
import beam.utils.EventReader
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.scalatest.AppendedClues.convertToClueful
import org.scalatest.BeforeAndAfterAllConfigMap
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

import scala.util.Random

class PersonAgentTripCostTest extends AnyFlatSpec with Matchers with BeamHelper with BeforeAndAfterAllConfigMap {

  private val filesPath = s"""$${beam.inputDirectory}"/../../test-resources/PersonAgentTripCostTestData"""

  private val seed = Random.nextInt()

  private val config: Config =
    ConfigFactory
      .parseString(
        s"""
           |matsim.modules.global.randomSeed = $seed
           |beam.outputs.events.eventsToWrite = "ActivityEndEvent,ActivityStartEvent,PersonEntersVehicleEvent,PersonLeavesVehicleEvent,ModeChoiceEvent,PathTraversalEvent,ReserveRideHailEvent,ReplanningEvent,RefuelSessionEvent,ChargingPlugInEvent,ChargingPlugOutEvent,ParkingEvent,LeavingParkingEvent,PersonCostEvent"
           |beam.agentsim.thresholdForWalkingInMeters = 1
           |beam.agentsim.agents.modalBehaviors.modeChoiceClass = "ModeChoiceRideHailIfAvailable"
           |beam.agentsim.agents.modalBehaviors.defaultValueOfTime = 8.0
           |beam.agentsim.agents.modalBehaviors.multinomialLogit.params.transfer = -1.4
           |beam.agentsim.agents.modalBehaviors.multinomialLogit.params.car_intercept = 10000.0
           |beam.agentsim.agents.modalBehaviors.multinomialLogit.params.walk_transit_intercept = -1000.0
           |beam.agentsim.agents.modalBehaviors.multinomialLogit.params.drive_transit_intercept = 2.0
           |beam.agentsim.agents.modalBehaviors.multinomialLogit.params.ride_hail_transit_intercept = 0.0
           |beam.agentsim.agents.modalBehaviors.multinomialLogit.params.ride_hail_intercept = 10.0
           |beam.agentsim.agents.modalBehaviors.multinomialLogit.params.ride_hail_pooled_intercept = 10.0
           |beam.agentsim.agents.modalBehaviors.multinomialLogit.params.walk_intercept = -1000.0
           |beam.agentsim.agents.modalBehaviors.multinomialLogit.params.bike_intercept = 2.0
           |beam.agentsim.agents.vehicles.replanOnTheFlyWhenHouseholdVehiclesAreNotAvailable = "true"
           |beam.outputs.events.fileOutputFormats = xml
           |beam.physsim.skipPhysSim = true
           |beam.routing.transitOnStreetNetwork = false
           |beam.agentsim.lastIteration = 0
           |beam.agentsim.tuning.transitCapacity = 0.0
           |beam.agentsim.agents.vehicles.sharedFleets = []
           |beam.agentsim.agents.plans {
           |  inputPlansFilePath = $filesPath/population.xml"
           |  inputPersonAttributesFilePath = $filesPath/populationAttributes.xml"
           |}
           |beam.agentsim.agents.households {
           |  inputFilePath = $filesPath/households.xml"
           |  inputHouseholdAttributesFilePath = $filesPath/householdAttributes.xml"
           |}
           |beam.agentsim.taz.filePath=$filesPath/taz-centers.csv"
           |beam.agentsim.taz.parkingFilePath = $filesPath/taz-parking.csv"
           |beam.agentsim.agents.vehicles.vehiclesFilePath = $filesPath/vehicles.csv"
           |beam.agentsim.agents.vehicles.vehicleTypesFilePath = $filesPath/vehicleTypes.csv"
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
           |    initialization.filePath = $filesPath/rideHailFleet.csv"
           |    initialization.initType="FILE"
           |    initialization.parking.filePath=$filesPath/taz-parking-empty.csv"
           |  }
           |]
        """.stripMargin
      )
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

  // this test was crafted to catch a specific bug described on issue #3746
  // the ride hail fleet consists of a single vehicle and 10 PersonAgents with the exact same plans want to use ride hail
  // forcing them to re-plan. When they do re-plan by taking their personal vehicles the bug is triggered
  "Person Agents" should "report PersonCost events related to personal vehicle trips with expected cost" in {

    val (matsimConfig, _, beamServices) = runBeamWithConfig(config)

    val carType = beamServices.beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
    val fuelPrice = beamServices.beamScenario.fuelTypePrices(FuelType.Gasoline)
    val relativeTolerance = 1e-3 // 0.1%
    val allEvents = EventReader.fromXmlFile(
      EventReader.getEventsFilePath(matsimConfig, "events", "xml").getAbsolutePath
    )

    val replanningCount = filterEventsAnd(
      allEvents,
      ("type", a => a.equals("Replanning"))
    ).size

    replanningCount should be > 0 withClue
    ", expecting replanning events to happen due to ride hail unavailability"

    val personAndVehicleIds = List("1", "2", "3", "4", "5", "6", "7", "8", "9", "10")

    personAndVehicleIds.foreach { id =>
      val events = filterEventsOr(
        allEvents,
        ("person", a => a.equals(id)),
        ("vehicle", a => a.equals(id))
      )

      val pteAndCost = filterEventsOr(
        events,
        ("type", a => a.equals("PathTraversal") || a.equals("PersonCost"))
      ).filter(e =>
        if (e.getEventType.equals("PersonCost")) e.getAttributes.get("mode").equals("car")
        else e.getAttributes.get("vehicle").equals(id)
      )

      var currentLegLength: Double = 0.0
      pteAndCost.foreach { e =>
        if (e.getEventType.equals("PathTraversal")) {
          currentLegLength = currentLegLength + e.getAttributes.get("length").toDouble
        } else {
          // time is set to zero since it will not matter (vehicleType.monetaryCostPerSecond is zero)
          val expectedCost = DrivingCost.estimateDrivingCost(currentLegLength, 0, carType, fuelPrice)
          val actualCost = e.getAttributes.get("netCost").toDouble - e.getAttributes.get("tollCost").toDouble
          currentLegLength = 0.0
          actualCost shouldEqual (expectedCost +- expectedCost * relativeTolerance) withClue
          ", a PersonCost event did not match the expected trip cost calculated from its PathTraversal events."
        }
      }
    }
  }

  def evaluateFilter(event: Event, filter: (String, String => Boolean)): Boolean = {
    event.getAttributes.containsKey(filter._1) && filter._2(event.getAttributes.get(filter._1))
  }

  def filterEventsAnd(events: IndexedSeq[Event], filters: (String, String => Boolean)*): IndexedSeq[Event] = {
    events.filter(event => filters.forall(filter => evaluateFilter(event, filter)))
  }

  def filterEventsOr(events: IndexedSeq[Event], filters: (String, String => Boolean)*): IndexedSeq[Event] = {
    events.filter(event => filters.exists(filter => evaluateFilter(event, filter)))
  }

}
