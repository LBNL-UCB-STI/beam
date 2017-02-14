package beam.metasim.sim

import akka.actor.ActorRef
import beam.metasim.agents._
import beam.metasim.akkaguice.ActorInject
import com.google.inject.{Inject, Injector, Singleton}
import org.matsim.core.controler.MatsimServices

/**
  * Created by sfeygin on 2/11/17.
  */
@Singleton
case class MetaSimServices @Inject()(protected val injector: Injector) extends ActorInject {

  val schedulerRef: ActorRef = injectTopActor[BeamAgentScheduler]

  val personAgentCreator: ActorRef = injectTopActor[PersonAgentCreator]

  val matsimServices: MatsimServices = injector.getInstance(classOf[MatsimServices])

}
