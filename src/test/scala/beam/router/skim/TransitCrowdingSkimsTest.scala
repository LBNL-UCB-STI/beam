package beam.router.skim

import java.io.File

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events.SpaceTime
import beam.router.Modes.BeamMode.WALK_TRANSIT
import beam.router.model.RoutingModel.TransitStopsInfo
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.{BeamHelper, BeamServices}
import org.matsim.api.core.v01.Id
import org.scalatest.{FlatSpec, Matchers}

/**
  *
  * @author Dmitry Openkov
  */
class TransitCrowdingSkimsTest extends FlatSpec with Matchers with BeamHelper {

  val constr: BeamServices => TransitCrowdingSkimmer =
    services => new TransitCrowdingSkimmer(services.matsimServices, services.beamScenario, services.beamConfig)

  "TransitCrowdingSkims" should "calculate occupancy level correctly" in {
    val basePath = System.getenv("PWD")
    val inputFilePath = s"$basePath/test/test-resources/beam/router/skim/transit-crowding-test-data.csv"
    val skimmer: TransitCrowdingSkimmer = ODSkimmerTest.createSkimmer(inputFilePath, constr)

    val trip = EmbodiedBeamTrip(IndexedSeq(createLeg("SF:7678110"), createLeg("BA:36R11")))
    val level = skimmer.readOnlySkim.getTransitOccupancyLevelForPercentile(trip, 90.1)
    level should be(0.24 +- 0.01)
  }

  private def createLeg(id: String): EmbodiedBeamLeg = {
    val vehicleId = Id.createVehicleId(id)
    val vehicleType = Id.create("BUS-DEFAULT", classOf[BeamVehicleType])
    val start = SpaceTime(128.0, 70.0, 100)
    val end = SpaceTime(128.0, 70.0, 10000)
    val path = BeamPath(
      IndexedSeq.empty,
      IndexedSeq.empty,
      Some(TransitStopsInfo("A", "A", vehicleId, 0, 9)),
      start,
      end,
      10000.0
    )
    EmbodiedBeamLeg(
      BeamLeg(1000, WALK_TRANSIT, 2000, path),
      vehicleId,
      vehicleType,
      asDriver = false,
      10.0,
      unbecomeDriverOnCompletion = false
    )
  }
}
