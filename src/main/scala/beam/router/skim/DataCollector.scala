package beam.router.skim

import beam.agentsim.infrastructure.taz.H3TAZ
import beam.sim.BeamScenario
import com.google.inject.Inject
import org.matsim.core.controler.MatsimServices
import org.matsim.core.controler.events.{IterationEndsEvent, StartupEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, StartupListener}

import scala.collection.immutable

class DataCollector @Inject()(beamScenario: BeamScenario, matsimServices: MatsimServices)
  extends StartupListener
    with IterationEndsListener {

  val h3taz: H3TAZ = new H3TAZ(matsimServices.getScenario, beamScenario.tazTreeMap)

  private var skims: immutable.Map[String, AbstractBeamSkimmer2] = immutable.Map(
    "Skimmer" -> new Skimmer2(h3taz),
    "ODSkimmer" -> new ODSkimmer2(h3taz)
  )

  override def notifyStartup(event: StartupEvent): Unit = {
    skims.values.foreach(matsimServices.getEvents.addHandler(_))
  }
  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    skims.values.foreach(_.persist(event))
  }
}
