package beam.router.skim

import beam.router
import beam.router.skim
import beam.sim.BeamServices
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable

object Skims extends LazyLogging {
  lazy val od_skimmer: ODSkims = lookup(SkimType.OD_SKIMMER).asInstanceOf[ODSkims]
  lazy val taz_skimmer: TAZSkims = lookup(SkimType.TAZ_SKIMMER).asInstanceOf[TAZSkims]
  lazy val dt_skimmer: DriveTimeSkims = lookup(SkimType.DT_SKIMMER).asInstanceOf[DriveTimeSkims]

  def setup(implicit beamServices: BeamServices): Unit = {
    val skimConfig = beamServices.beamConfig.beam.router.skim
    skims.put(SkimType.OD_SKIMMER, addEvent(new ODSkimmer(beamServices, skimConfig)))
    skims.put(SkimType.TAZ_SKIMMER, addEvent(new TAZSkimmer(beamServices, skimConfig)))
    skims.put(SkimType.DT_SKIMMER, addEvent(new DriveTimeSkimmer(beamServices, skimConfig)))

  }

  def clear(): Unit = {
    skims.clear()
  }

  private val skims = mutable.Map.empty[SkimType.Value, AbstractSkimmer]

  object SkimType extends Enumeration {
    val OD_SKIMMER: router.skim.Skims.SkimType.Value = Value("od-skimmer")
    val TAZ_SKIMMER: skim.Skims.SkimType.Value = Value("taz-skimmer")
    val DT_SKIMMER: skim.Skims.SkimType.Value = Value("drive-time-skimmer")
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
