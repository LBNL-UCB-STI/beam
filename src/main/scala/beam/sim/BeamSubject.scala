package beam.sim

import beam.router.skim.FlatSkimmer
import com.google.inject.{Inject, Injector}
import org.matsim.core.controler.MatsimServices
import org.matsim.core.controler.events.{IterationEndsEvent, StartupEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, StartupListener}

class BeamSubject @Inject()(val injector: Injector) extends StartupListener with IterationEndsListener {
  private var observers = List.empty[BeamObserver]

  val beamScenario: BeamScenario = injector.getInstance(classOf[BeamScenario])
  val matsimServices: MatsimServices = injector.getInstance(classOf[MatsimServices])

  def getAll = observers

  override def notifyStartup(event: StartupEvent): Unit = {
    // add observers here
    observers = List(new FlatSkimmer(beamScenario, matsimServices))

    // attach all handlers
    observers.foreach(matsimServices.getEvents.addHandler)
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    observers.foreach(_.notifyIterationEnds(event))
  }
}
