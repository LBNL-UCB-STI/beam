package beam.router.skim

import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging

trait SkimType {
  def getInstance(beamServices: BeamServices): AbstractSkimmer
}

case class ODSkimType(config: BeamConfig.Beam.Router.Skim$Elm) extends SkimType with LazyLogging {
  override def getInstance(beamServices: BeamServices): AbstractSkimmer = {
    new ODSkimmer(beamServices, config)
  }
}

case class CountSkimType(config: BeamConfig.Beam.Router.Skim$Elm) extends SkimType with LazyLogging {
  override def getInstance(beamServices: BeamServices): AbstractSkimmer = {
    new CountSkimmer(beamServices, config)
  }
}
