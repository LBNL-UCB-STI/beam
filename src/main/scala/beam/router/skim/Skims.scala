package beam.router.skim

import beam.sim.BeamServices

import scala.collection.immutable

object Skims {
  private type SkimType = String
  private var skims: immutable.Map[SkimType, AbstractSkimmer] = immutable.Map()

  def setup(beamServices: BeamServices): Unit = {
    skims = beamServices.beamConfig.beam.router.skim.skimmers.map { skimmerConfig =>
      val skimmer = skimmerConfig.skimType match {
        case "od-skim"    => new ODSkimmer(beamServices, skimmerConfig)
        case "count-skim" => new CountSkimmer(beamServices, skimmerConfig)
        case _ =>
          throw new RuntimeException("Unknown skim type")
      }
      beamServices.matsimServices.addControlerListener(skimmer)
      beamServices.matsimServices.getEvents.addHandler(skimmer)
      skimmerConfig.skimType -> skimmer
    }.toMap
  }

  def clear(): Unit = {
    skims = immutable.Map()
  }

  def lookup(skimType: String) = {
    skims.get(skimType).map(_.readOnlySkim)
  }
}
