package beam.sim

import beam.agentsim.events.PathTraversalEvent
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

  it should "Use RH_BEV_L5 to transfer agents." in {
    val config = ConfigFactory
      .parseString(s"""
           |beam.agentsim.lastIteration = 0
           |beam.outputs.events.fileOutputFormats = "xml"
         """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam-rh-ecav.conf"))
      .resolve()

    val matsimConfig = runBeamWithConfig(config)._1
    val filePath = getEventsFilePath(matsimConfig, "events", "xml").getAbsolutePath
    val events = fromXmlFile(filePath)
    val rhPTEEvents = getRHPathTraversalsWithPassengers(events, 1)

    rhPTEEvents.size shouldNot be(0) withClue ", expecting RH path traversal events with passengers"
    rhPTEEvents.map(_.vehicleType) should contain("RH_BEV_L5")

  }

  it should "Use RH_BEV to transfer agents." in {
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
    rhPTEEvents.map(_.vehicleType) should contain("RH_BEV")
  }

}
