package beam.router.skim

import beam.sim.BeamServices
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable

object Skims extends LazyLogging {
  private type SkimType = String
  private val skims: mutable.Map[SkimType, AbstractSkimmer] = mutable.Map()

  def setup(implicit beamServices: BeamServices): Unit = {
    beamServices.beamConfig.beam.router.skim.skimmers.foreach { skimmerConfig =>
      if (skimmerConfig.od_skimmer.isDefined) {
        skims.put(skimmerConfig.od_skimmer.get.skimType, addEvent(new ODSkimmer(beamServices, skimmerConfig)))
      } else if (skimmerConfig.count_skimmer.isDefined) {
        skims.put(skimmerConfig.count_skimmer.get.skimType, addEvent(new CountSkimmer(beamServices, skimmerConfig)))
      } else {
        logger.info("this Skimmer is not associated to an implementation")
      }
    }
  }

  private def addEvent(skimmer: AbstractSkimmer)(implicit beamServices: BeamServices): AbstractSkimmer = {
    beamServices.matsimServices.addControlerListener(skimmer)
    beamServices.matsimServices.getEvents.addHandler(skimmer)
    skimmer
  }

  def clear(): Unit = {
    skims.clear()
  }

  def lookup(skimType: String) = {
    skims.get(skimType).map(_.readOnlySkim)
  }
}
