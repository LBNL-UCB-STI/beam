package beam.router.skim

import beam.agentsim.infrastructure.taz.H3TAZ
import beam.sim.{BeamScenario, BeamServices}
import com.google.inject.Inject
import org.matsim.core.controler.MatsimServices
import org.matsim.core.controler.events.{IterationEndsEvent, StartupEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, StartupListener}

import scala.collection.immutable

class SkimManager @Inject()(beamServices: BeamServices)
  extends StartupListener
    with IterationEndsListener {

  val h3taz: H3TAZ = new H3TAZ(
    beamServices.matsimServices.getScenario,
    beamServices.beamScenario.tazTreeMap,
    beamServices.beamConfig.beam.skimmanager.h3.resolution,
    beamServices.beamConfig.beam.skimmanager.h3.lowerBoundResolution
  )

  private var skims: immutable.Map[String, AbstractSkimmer] = immutable.Map(
    "Skimmer" -> new Skimmer(beamServices, h3taz),
    "ODSkimmer" -> new ODSkimmer(beamServices, h3taz)
  )

  override def notifyStartup(event: StartupEvent): Unit = {
    skims.values.foreach(beamServices.matsimServices.getEvents.addHandler(_))
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    skims.values.foreach(_.persist(event))
    skims.values.foreach(_.aggregate(event))
  }
}
