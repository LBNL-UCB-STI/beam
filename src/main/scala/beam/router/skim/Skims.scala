package beam.router.skim

import beam.router
import beam.router.skim
import beam.router.skim.core.AbstractSkimmer.AGG_SUFFIX
import beam.router.skim.core.{
  AbstractSkimmer,
  AbstractSkimmerReadOnly,
  DriveTimeSkimmer,
  ODSkimmer,
  TAZSkimmer,
  TransitCrowdingSkimmer
}
import beam.router.skim.readonly.{DriveTimeSkims, ODSkims, TAZSkims, TransitCrowdingSkims}
import beam.sim.config.BeamConfig.Beam.Router
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.controler.MatsimServices

import scala.collection.mutable

class Skims @Inject()(
  matsimServices: MatsimServices,
  odSkimmer: ODSkimmer,
  tazSkimmer: TAZSkimmer,
  driveTimeSkimmer: DriveTimeSkimmer,
  transitCrowdingSkimmer: TransitCrowdingSkimmer,
) extends LazyLogging {

  import Skims.SkimType
  lazy val od_skimmer: ODSkims = lookup(SkimType.OD_SKIMMER).asInstanceOf[ODSkims]
  lazy val taz_skimmer: TAZSkims = lookup(SkimType.TAZ_SKIMMER).asInstanceOf[TAZSkims]
  lazy val dt_skimmer: DriveTimeSkims = lookup(SkimType.DT_SKIMMER).asInstanceOf[DriveTimeSkims]
  lazy val tc_skimmer: TransitCrowdingSkims = lookup(SkimType.TC_SKIMMER).asInstanceOf[TransitCrowdingSkims]

  private val skims = mutable.Map.empty[SkimType.Value, AbstractSkimmer]
  skims.put(SkimType.OD_SKIMMER, addEvent(odSkimmer))
  skims.put(SkimType.TAZ_SKIMMER, addEvent(tazSkimmer))
  skims.put(SkimType.DT_SKIMMER, addEvent(driveTimeSkimmer))
  skims.put(SkimType.TC_SKIMMER, addEvent(transitCrowdingSkimmer))

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
    val TC_SKIMMER: skim.Skims.SkimType.Value = Value("transit-crowding-skimmer")
    val AS_SKIMMER: router.skim.Skims.SkimType.Value = Value("activity-sim-skimmer")
  }

  def skimFileNames(skimCfg: Router.Skim) = IndexedSeq(
    SkimType.OD_SKIMMER  -> skimCfg.origin_destination_skimmer.fileBaseName,
    SkimType.TAZ_SKIMMER -> skimCfg.taz_skimmer.fileBaseName,
    SkimType.DT_SKIMMER  -> skimCfg.drive_time_skimmer.fileBaseName,
    SkimType.TC_SKIMMER  -> skimCfg.transit_crowding_skimmer.fileBaseName,
    SkimType.AS_SKIMMER  -> skimCfg.activity_sim_skimmer.fileBaseName,
  )

  def skimAggregatedFileNames(skimCfg: Router.Skim): IndexedSeq[(SkimType.Value, String)] =
    skimFileNames(skimCfg)
      .map { case (skimType, fileName) => skimType -> (fileName + AGG_SUFFIX) }
}
