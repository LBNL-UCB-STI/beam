package beam.sim

import beam.agentsim.events.{PathTraversalEvent, RefuelSessionEvent}
import beam.utils.EventReader.{fromXmlFile, getEventsFilePath}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.events.Event
import org.scalatest.AppendedClues.convertToClueful
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RideHailUsageTests extends AnyFlatSpec with Matchers with BeamHelper {

  def getRHPathTraversalsWithPassengers(
    events: Seq[Event],
    minimumNumberOfPassengers: Int = 1
  ): Iterable[PathTraversalEvent] = {
    val pathTraversalEvents = events
      .filter(e => PathTraversalEvent.EVENT_TYPE.equals(e.getEventType))
      .map(_.asInstanceOf[PathTraversalEvent])

    pathTraversalEvents.filter(pte =>
      pte.vehicleId.toString.contains("rideHail") && pte.numberOfPassengers >= minimumNumberOfPassengers
    )
  }

  val automatedRideHailVehicleType = "RH_BEV_L5"
  it should f"Use $automatedRideHailVehicleType to transfer agents, also RH_BEV_L5 should charge." in {
    val config = ConfigFactory
      .parseString(s"""
           |beam.agentsim.lastIteration = 0
           |beam.outputs.events.fileOutputFormats = "xml"
         """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam-withL5.conf"))
      .resolve()

    val matsimConfig = runBeamWithConfig(config)._1
    val filePath = getEventsFilePath(matsimConfig, "events", "xml").getAbsolutePath
    val events = fromXmlFile(filePath)
    val rhPTEEvents = getRHPathTraversalsWithPassengers(events, 1)

    rhPTEEvents.size should be > 0 withClue ", expecting RH path traversal events with passengers"
    rhPTEEvents.map(_.vehicleType) should contain(automatedRideHailVehicleType)

    val refuelSessionEvents = events.filter(e => RefuelSessionEvent.EVENT_TYPE.equals(e.getEventType))
    refuelSessionEvents.size should be > 0 withClue ", expecting charging events"
    refuelSessionEvents.map(e => e.getAttributes.get(RefuelSessionEvent.ATTRIBUTE_VEHICLE_TYPE)) should contain(
      automatedRideHailVehicleType
    ) withClue f", expecting $automatedRideHailVehicleType to charge"
  }

  val nonAutomatedRideHailVehicleType = "RH_BEV"
  it should f"Use $nonAutomatedRideHailVehicleType to transfer agents." in {
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