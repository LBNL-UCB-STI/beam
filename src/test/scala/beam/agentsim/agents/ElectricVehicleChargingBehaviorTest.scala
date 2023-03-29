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

class ElectricVehicleChargingBehaviorTest
    extends AnyFlatSpec
    with Matchers
    with BeamHelper
    with BeforeAndAfterAllConfigMap {

  private val filesPath = s"""$${beam.inputDirectory}"/../../test-resources/ElectricVehicleChargingBehaviorTestData"""

  private val defaultConfig: Config =
    ConfigFactory
      .parseString(
        s"""
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
      EventReader.getEventsFilePath(matsimConfig, "events", "xml", 0).getAbsolutePath
    )

    val homeTaz = "10"
    val workTaz = "11"

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

    val vehicleIds = findAllElectricVehicles(events).map(id => id.toString)
    vehicleIds.size shouldEqual 50 withClue ", expecting 50 electric vehicles."

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
      EventReader.getEventsFilePath(matsimConfig, "events", "xml", 0).getAbsolutePath
    )

    val centerTAZs = List("15", "16")
    val borderTAZs = List("20", "21", "30", "31")

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

    val vehicleIds = findAllElectricVehicles(events).map(id => id.toString)
    vehicleIds.size shouldEqual 50 withClue ", expecting 50 electric vehicles."

    centerPluginEvents.size + borderPluginEvents.size shouldEqual 200 withClue
    ", expecting 4 enroute events for each of the 50 vehicles."

    centerPluginEvents.size should be > borderPluginEvents.size withClue
    ", agents should prefer center chargers for enrouting (smaller EnrouteDetourCost)."
  }

  "Electric vehicles" should "always enroute when there is not enough energy to reach their destination choosing smaller ParkingTicketCost." in {
    val config = ConfigFactory
      .parseString(
        s"""
           |beam.agentsim.taz.parkingFilePath = $filesPath/taz-parking-enroute-only-expensive-bottom-no-center.csv"
           |beam.agentsim.agents.vehicles.vehicleTypesFilePath = $filesPath/vehicleTypes-very-low-capacity.csv"
        """.stripMargin
      )
      .withFallback(defaultConfig)
      .resolve()

    val (matsimConfig, _, _) = runBeamWithConfig(config)

    val events = EventReader.fromXmlFile(
      EventReader.getEventsFilePath(matsimConfig, "events", "xml", 0).getAbsolutePath
    )

    val topFreeTAZs = List("20", "21")
    val bottomExpensiveTAZs = List("30", "31")

    val topPluginEvents = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent")),
      ("parkingTaz", (a: String) => topFreeTAZs.contains(a))
    )
    val bottomPluginEvents = filterEvents(
      events,
      ("type", a => a.equals("ChargingPlugInEvent")),
      ("parkingTaz", (a: String) => bottomExpensiveTAZs.contains(a))
    )

    val vehicleIds = findAllElectricVehicles(events).map(id => id.toString)
    vehicleIds.size shouldEqual 50 withClue ", expecting 50 electric vehicles."

    topPluginEvents.size + bottomPluginEvents.size should be >= 200 withClue
    ", expecting at least 4 enroute events for each of the 50 vehicles."

    topPluginEvents.size should be > bottomPluginEvents.size withClue
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
