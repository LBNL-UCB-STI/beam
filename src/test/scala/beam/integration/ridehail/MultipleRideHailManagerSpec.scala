package beam.integration.ridehail

import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent, RideHailReservationConfirmationEvent}
import beam.integration.{IntegrationSpecCommon, StartWithCustomConfig}
import beam.sim.BeamHelper
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigValueFactory
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle
import org.scalatest.AppendedClues.convertToClueful
import org.scalatest.Inspectors.forAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class MultipleRideHailManagerSpec extends AnyWordSpecLike with Matchers with BeamHelper with IntegrationSpecCommon {

  private val start = new StartWithCustomConfig(
    testConfig("test/input/beamville/beam-multiple-rhm.conf")
      .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml"))
      .resolve()
  )
  private val events = start.events

  "Running beam with multiple RideHailManagers" must {
    "produce PathTraversalEvents for vehicles of each RHM" in {
      val rhPTE = events.collect {
        case pte: PathTraversalEvent if pte.vehicleId.toString.startsWith("rideHail") => pte
      }
      val groupedByFleetId = rhPTE.groupBy(pte => getFleetId(pte.vehicleId))
      groupedByFleetId.keySet shouldBe Set("Uber", "Lyft", "Cruise")
    }
    "have RideHailManagers use only supported modes" in {
      val reservationConfirmations = events.filter(e =>
        e.getEventType == "RideHailReservationConfirmation" && e.getAttributes.get("errorCode").isEmpty
      )
      val (soloReservations, pooledReservations) =
        reservationConfirmations.partition(_.getAttributes.get("reservationType") == "Solo")
      pooledReservations should not be empty withClue "No pooled rides happened. Cannot validate the RHM name"
      withClue("Only Lyft provides pooled trips") {
        forAll(pooledReservations) { pooled =>
          pooled.getAttributes.get("vehicle") should endWith("Lyft")
        }
      }
      soloReservations should not be empty withClue "No solo rides happened. Cannot validate the RHM name"
      withClue(
        "Uber provides only solo trips. Lyft provides pooled trips, but it also can be a solo trip because no other passengers are there."
      ) {
        atLeast(
          soloReservations.size / 2,
          soloReservations.map(_.toString)
        ) should include regex """vehicle="rideHailVehicle-\d+@Uber""""
      }
    }
  }

  private def getFleetId(vehicleId: Id[Vehicle]): String = vehicleId.toString.split('@')(1)

}
