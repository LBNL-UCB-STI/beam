package beam.sim

import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent, RefuelSessionEvent, ReplanningEvent}
import beam.integration.IntegrationSpecCommon
import beam.utils.EventReader.{fromXmlFile, getEventsFilePath}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.events.Event
import org.scalatest.AppendedClues.convertToClueful
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RideHailUsageTests extends AnyFlatSpec with Matchers with BeamHelper with IntegrationSpecCommon {

  def getRHPathTraversalsWithPassengers(
    events: Seq[Event],
    minimumNumberOfPassengers: Int
  ): Iterable[PathTraversalEvent] = {
    val pathTraversalEvents = events
      .filter(e => PathTraversalEvent.EVENT_TYPE.equals(e.getEventType))
      .map(_.asInstanceOf[PathTraversalEvent])

    pathTraversalEvents.filter(pte =>
      pte.vehicleId.toString.contains("rideHail") && pte.numberOfPassengers >= minimumNumberOfPassengers
    )
  }

  it should "Use RH_BEV_L5 to transfer agents" in {
    val automatedRideHailVehicleType = "RH_BEV_L5"
    val config = ConfigFactory
      .parseString(s"""
           |beam.agentsim.lastIteration = 0
           |beam.agentsim.taz.parkingFilePath = $${beam.inputDirectory}"/parking/taz-parking-one-rh-stall.csv"
           |beam.outputs.events.fileOutputFormats = "xml"
           |beam.agentsim.agents.households.inputFilePath = $${beam.inputDirectory}"/households-no-vehicles.xml"
           |beam.agentsim.agents.vehicles.replanOnTheFlyWhenHouseholdVehiclesAreNotAvailable = true
         """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam-withL5.conf"))
      .resolve()

    val matsimConfig = runBeamWithConfig(config)._1
    val filePath = getEventsFilePath(matsimConfig, "events", "xml").getAbsolutePath
    val events = fromXmlFile(filePath)
    val rhPTEEvents = getRHPathTraversalsWithPassengers(events, 1)

    rhPTEEvents.size should be > 0 withClue ", expecting RH path traversal events with passengers"
    rhPTEEvents.map(_.vehicleType) should contain(automatedRideHailVehicleType)

    val refuelSessionEvents = events.filter(e =>
      RefuelSessionEvent.EVENT_TYPE.equals(e.getEventType) && e.getAttributes.get("vehicle").contains("-L5")
    )
    refuelSessionEvents.size should be > 20 withClue ", expecting about 21 charging events"
    refuelSessionEvents.map(e => e.getAttributes.get(RefuelSessionEvent.ATTRIBUTE_VEHICLE_TYPE)) should contain(
      automatedRideHailVehicleType
    ) withClue f", expecting $automatedRideHailVehicleType to charge"

    def getTimeDuration(event: Event) = {
      val attributes = event.getAttributes
      (attributes.get("time").toFloat, attributes.get("duration").toFloat)
    }

    val firstEventEndOfCharging = refuelSessionEvents.headOption
      .map(refuelEvent => {
        val (time, duration) = getTimeDuration(refuelEvent)
        time + duration
      })
      .get

    refuelSessionEvents.tail.foldLeft(firstEventEndOfCharging) { case (previousEventEndOfCharging, refuelEvent) =>
      val (currentEventTime, currentEventDuration) = getTimeDuration(refuelEvent)
      previousEventEndOfCharging should be <= currentEventTime withClue ", there is only one RH stall, charging events should go one by one"
      currentEventTime + currentEventDuration
    }

    def isReplanningOrModeChoiceEventForPerson2(event: Event) = {
      val attributes = event.getAttributes
      val person = attributes.getOrDefault("person", "")
      val isModeChoice = ModeChoiceEvent.EVENT_TYPE.equalsIgnoreCase(event.getEventType)
      val isReplanning = ReplanningEvent.EVENT_TYPE.equalsIgnoreCase(event.getEventType)
      person == "2" && (isModeChoice || isReplanning)
    }

    val replanAndModeChoiceForPerson2 = events
      .filter(e => isReplanningOrModeChoiceEventForPerson2(e))
      .dropWhile(e => !ReplanningEvent.EVENT_TYPE.equalsIgnoreCase(e.getEventType))
      .map(_.getAttributes)
      .take(2)

    replanAndModeChoiceForPerson2(0)
      .getOrDefault(
        ReplanningEvent.ATTRIBUTE_REPLANNING_REASON,
        ""
      ) shouldBe "HouseholdVehicleNotAvailable CAR" withClue ", expected replanning because there are no household vehicles available"

    replanAndModeChoiceForPerson2(1)
      .getOrDefault(
        ModeChoiceEvent.ATTRIBUTE_MODE,
        ""
      ) shouldBe "ride_hail" withClue ", expected RH usage after replanning"
  }

  it should "Use RH_BEV to transfer agents." in {
    val nonAutomatedRideHailVehicleType = "RH_BEV"
    val config = ConfigFactory
      .parseString(s"""
                      |beam.agentsim.lastIteration = 0
                      |beam.outputs.events.fileOutputFormats = "xml"
         """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

    val matsimConfig = runBeamWithConfig(config)._1
    val filePath = getEventsFilePath(matsimConfig, "events", "xml").getAbsolutePath
    val events = fromXmlFile(filePath)
    val rhPTEEvents = getRHPathTraversalsWithPassengers(events, 1)

    rhPTEEvents.size should be > 0 withClue ", expecting RH path traversal events with passengers"
    rhPTEEvents.map(_.vehicleType) should contain(nonAutomatedRideHailVehicleType)
  }

}
