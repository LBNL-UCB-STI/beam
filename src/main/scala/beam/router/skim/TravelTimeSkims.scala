package beam.router.skim

import beam.sim.BeamServices

case class TravelTimeSkims(beamServices: BeamServices) extends AbstractSkimmerReadOnly(beamServices) {}
