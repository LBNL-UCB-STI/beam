package beam.metasim.sim

import akka.actor.{ActorRef, ActorSystem}
import beam.metasim.agents._
import beam.metasim.akkaguice.ActorInject
import com.google.inject.{Inject, Injector, Singleton}
import glokka.Registry
import org.matsim.core.controler.MatsimServices

/**
  * Created by sfeygin on 2/11/17.
  */
@Singleton
case class MetasimServices @Inject()(protected val injector: Injector) extends ActorInject {
  val schedulerRef: ActorRef = injectTopActor[BeamAgentScheduler]
  val matsimServices: MatsimServices = injector.getInstance(classOf[MatsimServices])
  val actorRegistry:ActorRef=Registry.start(injector.getInstance(classOf[ActorSystem]),"actor-registry")
}
