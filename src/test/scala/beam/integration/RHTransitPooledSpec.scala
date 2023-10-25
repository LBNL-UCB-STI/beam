package beam.integration

import beam.sim.BeamHelper
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigValueFactory}
import org.matsim.api.core.v01.events.Event
import org.scalatest.AppendedClues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class RHTransitPooledSpec
    extends AnyWordSpecLike
    with Matchers
    with BeamHelper
    with IntegrationSpecCommon
    with AppendedClues {

  def getModesOtherThan(excludedMode: String, simulationModes: Map[String, Int]): Int = {
    simulationModes.foldLeft(0) {
      case (countOfModes, (mode, _)) if mode == excludedMode => countOfModes
      case (countOfModes, (_, count))                        => countOfModes + count
    }
  }

  def getClueText(simulationModes: Map[String, Int]): String = {
    val allModesString = simulationModes.map { case (mode, count) => s"$mode:$count" }.mkString(", ")
    s", all modes are: $allModesString"
  }

  def baseBeamvilleUrbansimConfig: Config = testConfig("test/input/sf-light/sf-light-1k.conf")
    .withValue("beam.agentsim.lastIteration", ConfigValueFactory.fromAnyRef("0"))
    .withValue("beam.urbansim.fractionOfModesToClear.allModes", ConfigValueFactory.fromAnyRef("1.0"))
    .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml"))
    .withValue("beam.physsim.skipPhysSim", ConfigValueFactory.fromAnyRef("true"))
    .withValue("beam.agentsim.agents.vehicles.fractionOfInitialVehicleFleet", ConfigValueFactory.fromAnyRef("10.0"))
    .withValue("beam.agentsim.agents.vehicles.fractionOfPeopleWithBicycle", ConfigValueFactory.fromAnyRef("10.0"))

  "Running beam with high intercept for RH transit and low intercepts for regular RH" must {
    "use RH transit with pooled trips" in {
      val theRun: StartWithCustomConfig = new StartWithCustomConfig(
        baseBeamvilleUrbansimConfig
          .withValue(
            s"beam.agentsim.agents.modalBehaviors.multinomialLogit.params.ride_hail_intercept",
            ConfigValueFactory.fromAnyRef(-9999)
          )
          .withValue(
            s"beam.agentsim.agents.modalBehaviors.multinomialLogit.params.ride_hail_pooled_intercept",
            ConfigValueFactory.fromAnyRef(-9999)
          )
          .withValue(
            s"beam.agentsim.agents.modalBehaviors.multinomialLogit.params.ride_hail_transit_intercept",
            ConfigValueFactory.fromAnyRef(9999)
          )
          .resolve()
      )

      val rideHailTransitModeChoiceEvents = theRun.events.filter(e => {
        val attributes = e.getAttributes

        lazy val isModeChoice = e.getEventType == "ModeChoice"
        lazy val isRHTransit = attributes.get("mode") == "ride_hail_transit"

        isModeChoice && isRHTransit
      })

      val rideHailTransitUsers =
        rideHailTransitModeChoiceEvents.map(e => e.getAttributes.getOrDefault("person", "")).toSet

      val rideHailPooledModeChoiceEvents = theRun.events.filter(e => {
        val attributes = e.getAttributes

        lazy val isModeChoice = e.getEventType == "ModeChoice"
        lazy val isRHPooled = attributes.get("mode") == "ride_hail_pooled"

        isModeChoice && isRHPooled
      })

      val rideHailPooledUsers =
        rideHailPooledModeChoiceEvents.map(e => e.getAttributes.getOrDefault("person", "")).toSet

      val personEntersRideHailEvents = theRun.events.filter(e => {
        val attributes = e.getAttributes

        lazy val isPEV = e.getEventType == "PersonEntersVehicle"
        lazy val isRH = attributes.get("vehicle").startsWith("rideHail")

        isPEV && isRH
      })

      val rideHailTransitVehicles = personEntersRideHailEvents
        .filter(pev => rideHailTransitUsers.contains(pev.getAttributes.get("person")))
        .map(e => e.getAttributes.get("vehicle"))
        .toSet

      val rideHailPooledVehicles = personEntersRideHailEvents
        .filter(pev => rideHailPooledUsers.contains(pev.getAttributes.get("person")))
        .map(e => e.getAttributes.get("vehicle"))
        .toSet

      def getPassengers(e: Event): Int = e.getAttributes.getOrDefault("numPassengers", "0").toInt

      val rideHailTransitPathTraversalEvents = theRun.events.filter(e => {
        val attributes = e.getAttributes

        lazy val isPTE = e.getEventType == "PathTraversal"
        lazy val vehicle = attributes.get("vehicle")
        lazy val isRH = vehicle.startsWith("rideHail")
        lazy val isTransit = rideHailTransitVehicles.contains(vehicle)
        lazy val vehicleNotEmpty = getPassengers(e) > 0

        isPTE && isRH && isTransit && vehicleNotEmpty
      })

      val rideHailPooledPathTraversalEvents = theRun.events.filter(e => {
        val attributes = e.getAttributes

        lazy val isPTE = e.getEventType == "PathTraversal"
        lazy val vehicle = attributes.get("vehicle")
        lazy val isRH = vehicle.startsWith("rideHail")
        lazy val isPooled = rideHailPooledVehicles.contains(vehicle)
        lazy val vehicleNotEmpty = getPassengers(e) > 0

        isPTE && isRH && isPooled && vehicleNotEmpty
      })

      rideHailPooledPathTraversalEvents.size should be(0)
      rideHailTransitPathTraversalEvents.size should be > 0

    }
  }
}
