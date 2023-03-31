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

    val homeTaz = "10"
    val workTaz = "11"
    val unsuitableChargersTAZs = List("18", "28", "38")
    val fastChargersTAZs = List("15", "16", "20", "21", "30", "31")

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

    val centerTAZs = List("15", "16")
    val borderTAZs = List("20", "21", "30", "31")
    val unsuitableChargersTAZs = List("18", "28", "38")

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

    val freeChargersTAZs = List("20", "31")
    val expensiveChargersTAZs = List("30", "21")
    val unsuitableChargersTAZs = List("15", "16", "18", "28", "38")

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
