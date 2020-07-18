package beam.router.skim

import beam.router
import beam.router.skim
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.controler.MatsimServices

import scala.collection.mutable

class Skims @Inject()(
  matsimServices: MatsimServices,
  odSkimmer: ODSkimmer,
  tazSkimmer: TAZSkimmer,
  driveTimeSkimmer: DriveTimeSkimmer
) extends LazyLogging {

  import Skims.SkimType
  lazy val od_skimmer: ODSkims = lookup(SkimType.OD_SKIMMER).asInstanceOf[ODSkims]
  lazy val taz_skimmer: TAZSkims = lookup(SkimType.TAZ_SKIMMER).asInstanceOf[TAZSkims]
  lazy val dt_skimmer: DriveTimeSkims = lookup(SkimType.DT_SKIMMER).asInstanceOf[DriveTimeSkims]

  private val skims = mutable.Map.empty[SkimType.Value, AbstractSkimmer]
  skims.put(SkimType.OD_SKIMMER, addEvent(odSkimmer))
  skims.put(SkimType.TAZ_SKIMMER, addEvent(tazSkimmer))
  skims.put(SkimType.DT_SKIMMER, addEvent(driveTimeSkimmer))

  private def addEvent(skimmer: AbstractSkimmer): AbstractSkimmer = {
    matsimServices.addControlerListener(skimmer)
    matsimServices.getEvents.addHandler(skimmer)
    skimmer
  }

  private def lookup(skimType: SkimType.Value): AbstractSkimmerReadOnly = {
    skims.get(skimType).map(_.readOnlySkim).getOrElse(throw new RuntimeException(s"Skims $skimType does not exist"))
  }
}

object Skims {

  object SkimType extends Enumeration {
    val OD_SKIMMER: router.skim.Skims.SkimType.Value = Value("od-skimmer")
    val TAZ_SKIMMER: skim.Skims.SkimType.Value = Value("taz-skimmer")
    val DT_SKIMMER: skim.Skims.SkimType.Value = Value("drive-time-skimmer")
  }

}
