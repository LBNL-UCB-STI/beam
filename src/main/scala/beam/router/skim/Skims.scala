package beam.router.skim

import beam.router
import beam.router.skim
import beam.sim.BeamServices
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

object Skims extends LazyLogging {
  lazy val od_skimmer: ODSkims = lookup(SkimType.OD_SKIMMER).asInstanceOf[ODSkims]
  lazy val taz_skimmer: TAZSkims = lookup(SkimType.TAZ_SKIMMER).asInstanceOf[TAZSkims]
  lazy val dt_skimmer: DriveTimeSkims = lookup(SkimType.DT_SKIMMER).asInstanceOf[DriveTimeSkims]

  def setup(implicit beamServices: BeamServices): Unit = {
    val skimConfig = beamServices.beamConfig.beam.router.skim
    val odSkimmer = new ODSkimmer(beamServices, skimConfig)
    val tazSkimmer = new TAZSkimmer(beamServices, skimConfig)
    val dtSkimmer = new DriveTimeSkimmer(beamServices, skimConfig)

    if (beamServices.beamConfig.beam.agentsim.firstIteration == 0 &&
        beamServices.beamConfig.beam.warmStart.enabled) {
      val futures = Future.sequence(
        Seq(
          odSkimmer.lazyLoadAggregatedSkimFromFile(),
          tazSkimmer.lazyLoadAggregatedSkimFromFile(),
          dtSkimmer.lazyLoadAggregatedSkimFromFile()
        )
      )
      Await.result(futures, 20.minutes)
    }

    skims.put(SkimType.OD_SKIMMER, addEvent(odSkimmer))
    skims.put(SkimType.TAZ_SKIMMER, addEvent(tazSkimmer))
    skims.put(SkimType.DT_SKIMMER, addEvent(dtSkimmer))
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
