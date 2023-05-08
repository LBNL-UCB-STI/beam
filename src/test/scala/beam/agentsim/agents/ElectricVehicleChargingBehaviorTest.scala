package beam.agentsim.agents

import beam.agentsim.events.PathTraversalEvent
import beam.sim.BeamHelper
import beam.utils.EventReader
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.vehicles.Vehicle
import org.scalatest.AppendedClues.convertToClueful
import org.scalatest.BeforeAndAfterAllConfigMap
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

import scala.util.Random

class ElectricVehicleChargingBehaviorTest
  extends AnyFlatSpec
    with Matchers
    with BeamHelper
    with BeforeAndAfterAllConfigMap {

  private val filesPath = s"""$${beam.inputDirectory}"/../../test-resources/ElectricVehicleChargingBehaviorTestData"""

  private val seed = Random.nextInt()

  private val defaultConfig: Config =
    ConfigFactory
      .parseString(
        s"""
           |matsim.modules.global.randomSeed = $seed
           |beam.outputs.events.fileOutputFormats = xml
           |beam.physsim.skipPhysSim = true
           |beam.routing.transitOnStreetNetwork = false
           |beam.agentsim.lastIteration = 0
           |beam.agentsim.tuning.transitCapacity = 0.0
           |beam.agentsim.agents.rideHail.managers = [
           |  {
           |    initialization.procedural.fractionOfInitialVehicleFleet = 0
           |    initialization.procedural.vehicleTypeId = "beamVilleCar"
           |  }
           |]
           |beam.agentsim.agents.vehicles.sharedFleets = []
           |beam.agentsim.agents.vehicles.enroute.noRefuelThresholdOffsetInMeters = 0.0
           |beam.agentsim.agents.plans {
           |  inputPlansFilePath = $filesPath/population.xml"
           |  inputPersonAttributesFilePath = $filesPath/populationAttributes.xml"
           |}
           |beam.agentsim.agents.households {
           |  inputFilePath = $filesPath/households.xml"
           |  inputHouseholdAttributesFilePath = $filesPath/householdAttributes.xml"
           |}
           |beam.agentsim.taz.filePath=$filesPath/taz-centers.csv"
           |beam.agentsim.agents.vehicles.vehiclesFilePath = $filesPath/vehicles.csv"
        """.stripMargin
      )
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

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
      .withFallback(defaultConfig)
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
      .withFallback(defaultConfig)
      .resolve()

    val (matsimConfig, _, _) = runBeamWithConfig(config)

    val events = EventReader.fromXmlFile(
      EventReader.getEventsFilePath(matsimConfig, "events", "xml").getAbsolutePath
    )

    val centerTAZs = List("10", "11")
    val borderTAZs = List("20", "21", "30", "31")
    val unsuitableChargersTAZs = List("18", "19", "28", "29", "38", "39")

    val centerPluginEvents = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent")),
      ("parkingTaz", (a: String) => centerTAZs.contains(a))
    )
    val borderPluginEvents = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent")),
      ("parkingTaz", (a: String) => borderTAZs.contains(a))
    )
    val unsuitablePluginEvents = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent")),
      ("parkingTaz", (a: String) => unsuitableChargersTAZs.contains(a))
    )

    val vehicleIds = findAllElectricVehicles(events).map(id => id.toString)
    vehicleIds.size shouldEqual 50 withClue ", expecting 50 electric vehicles."

    unsuitablePluginEvents.size shouldEqual 0 withClue ", vehicles should not be connecting to chargers without sufficient power rating."

    centerPluginEvents.size + borderPluginEvents.size shouldEqual 200 withClue
      ", expecting 4 enroute events for each of the 50 vehicles."

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
      .withFallback(defaultConfig)
      .resolve()

    val (matsimConfig, _, _) = runBeamWithConfig(config)

    val events = EventReader.fromXmlFile(
      EventReader.getEventsFilePath(matsimConfig, "events", "xml").getAbsolutePath
    )

    val freeChargersTAZs = List("20", "21", "30", "31")
    val expensiveChargersTAZs = List("22", "23", "32", "33")
    val unsuitableChargersTAZs = List("10", "11", "18", "19", "28", "29", "38", "39")

    val freePluginEvents = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent")),
      ("parkingTaz", (a: String) => freeChargersTAZs.contains(a))
    )
    val expensivePluginEvents = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent")),
      ("parkingTaz", (a: String) => expensiveChargersTAZs.contains(a))
    )
    val unsuitablePluginEvents = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent")),
      ("parkingTaz", (a: String) => unsuitableChargersTAZs.contains(a))
    )

    val vehicleIds = findAllElectricVehicles(events).map(id => id.toString)
    vehicleIds.size shouldEqual 50 withClue ", expecting 50 electric vehicles."

    unsuitablePluginEvents.size shouldEqual 0 withClue
      ", vehicles should not be connecting to chargers without sufficient power rating."

    freePluginEvents.size + expensivePluginEvents.size should be >= 200 withClue
      ", expecting at least 4 enroute events for each of the 50 vehicles."

    freePluginEvents.size should be > expensivePluginEvents.size withClue
      ", agents should prefer top chargers for enrouting (smaller ParkingTicketCost)."
  }

  def filterEvents(events: IndexedSeq[Event], filters: (String, String => Boolean)*): IndexedSeq[Event] = {
    events.filter(event =>
      filters.forall(filter =>
        event.getAttributes.containsKey(filter._1) && filter._2(event.getAttributes.get(filter._1))
      )
    )
  }

  def findAllElectricVehicles(events: IndexedSeq[Event]): IndexedSeq[Id[Vehicle]] = {
    events.collect { case pte: PathTraversalEvent if pte.primaryFuelType == "Electricity" => pte.vehicleId }.distinct
  }
}
