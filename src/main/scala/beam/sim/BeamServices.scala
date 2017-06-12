package beam.sim

import akka.actor.{ActorRef, ActorSystem}
import beam.playground.akkaguice.ActorInject
import beam.sim.config.{BeamConfig }
import beam.agentsim.events.AgentsimEventsBus
import beam.router.BoundingBox
import com.google.inject.{Inject, Injector, Singleton}
import glokka.Registry
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Id }
import org.matsim.core.controler._

/**
  * Created by sfeygin on 2/11/17.
  */
@Singleton
case class BeamServices @Inject()(protected val injector: Injector) extends ActorInject {
  val matsimServices: MatsimServices = injector.getInstance(classOf[MatsimServices])
  val bbox: BoundingBox = new BoundingBox()
  val controler: ControlerI = injector.getInstance(classOf[ControlerI])
  var beamConfig: BeamConfig = injector.getInstance(classOf[BeamConfig])
  val agentSimEventsBus = new AgentsimEventsBus
  val registry: ActorRef = Registry.start(injector.getInstance(classOf[ActorSystem]), "actor-registry")

  //TODO find a better way to inject the router, for now this is initilized inside Agentsim.notifyStartup
  var beamRouter : ActorRef = _
  var physSim: ActorRef = _
  var schedulerRef: ActorRef =_
  var taxiManager: ActorRef = _
  var popMap: Option[Map[Id[Person], Person]] = None
}
