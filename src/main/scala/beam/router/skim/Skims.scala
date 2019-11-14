package beam.router.skim

import beam.sim.BeamServices

import scala.collection.mutable
import beam.sim.config.BeamConfig

object Skims {
  private val skims: mutable.Map[String, AbstractSkimmer] = mutable.Map()

  def setup(beamServices: BeamServices, config: BeamConfig.Beam.Router.Skim.Skimmers$Elm) = {
    val skimmer = config.skimType match {
      case "od-skim"    => new ODSkimmer(beamServices, config)
      case "count-skim" => new CountSkimmer(beamServices, config)
      case _ =>
        throw new RuntimeException("Unknown skim type")
    }
    beamServices.matsimServices.addControlerListener(skimmer)
    beamServices.matsimServices.getEvents.addHandler(skimmer)
    skims.put(config.skimType, skimmer)
  }

  def lookup(skimType: String) = {
    skims.get(skimType).map(_.readOnlySkim)
  }
}
