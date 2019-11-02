package beam.router.skim

import beam.agentsim.infrastructure.taz.H3TAZ
import beam.sim.BeamServices

import scala.collection.immutable

abstract class ReadOnlySkim(beamServices: BeamServices, h3taz: H3TAZ) extends AbstractSkimmer(beamServices, h3taz) {



}
