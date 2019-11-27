package beam.router.skim

import beam.router
import beam.router.skim
import beam.sim.BeamServices
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable

object Skims extends LazyLogging {
  lazy val od_skimmer: ODSkims = lookup(SkimType.OD_SKIMMER).asInstanceOf[ODSkims]
  lazy val count_skimmer: CountSkims = lookup(SkimType.COUNT_SKIMMER).asInstanceOf[CountSkims]
  lazy val tt_skimmer: TravelTimeSkims = lookup(SkimType.TT_SKIMMER).asInstanceOf[TravelTimeSkims]

  def setup(implicit beamServices: BeamServices): Unit = {
    val skimConfig = beamServices.beamConfig.beam.router.skim
    skims.put(SkimType.OD_SKIMMER, addEvent(new ODSkimmer(beamServices, skimConfig)))
    skims.put(SkimType.COUNT_SKIMMER, addEvent(new CountSkimmer(beamServices, skimConfig)))
    skims.put(SkimType.TT_SKIMMER, addEvent(new TravelTimeSkimmer(beamServices, skimConfig)))
  }

  def clear(): Unit = {
    skims.clear()
  }

  private val skims = mutable.Map.empty[SkimType.Value, AbstractSkimmer]

  object SkimType extends Enumeration {
    val OD_SKIMMER: router.skim.Skims.SkimType.Value = Value("od-skimmer")
    val COUNT_SKIMMER: skim.Skims.SkimType.Value = Value("count-skimmer")
    val TT_SKIMMER: skim.Skims.SkimType.Value = Value("tt-skimmer")
  }

  private def addEvent(skimmer: AbstractSkimmer)(implicit beamServices: BeamServices): AbstractSkimmer = {
    beamServices.matsimServices.addControlerListener(skimmer)
    beamServices.matsimServices.getEvents.addHandler(skimmer)
    skimmer
  }

  private def lookup(skimType: SkimType.Value): AbstractSkimmerReadOnly = {
    skims.get(skimType).map(_.readOnlySkim).getOrElse(throw new RuntimeException(s"Skims $skimType does not exist"))
  }
}
