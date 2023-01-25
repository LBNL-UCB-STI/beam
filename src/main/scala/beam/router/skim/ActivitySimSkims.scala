package beam.router.skim

import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode._
import beam.router.skim.ActivitySimSkimmer.ActivitySimSkimmerInternal
import beam.router.skim.SkimsUtils.distanceAndTime
import beam.router.skim.core.AbstractSkimmerReadOnly
import beam.sim.BeamScenario
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.Id

case class ActivitySimSkims(beamConfig: BeamConfig, beamScenario: BeamScenario) extends AbstractSkimmerReadOnly
