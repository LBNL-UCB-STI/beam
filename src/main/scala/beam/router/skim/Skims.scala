package beam.router.skim

import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Router.Skim$Elm

object Skims {
  def lookup(config: BeamConfig.Beam.Router.Skim$Elm): SkimType = {
    config.skimType match {
      case "origin-destination-skimmer" => ODSkimType(config)
      case "count-skimmer" => CountSkimType(config)
      case _ =>
        throw new RuntimeException("Unknown skim type")
    }
  }
}
