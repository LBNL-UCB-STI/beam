package beam.sim

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.util.Timeout
import beam.playground.akkaguice.ActorInject
import beam.sim.config.BeamConfig
import beam.agentsim.events.AgentsimEventsBus
import com.google.inject.{Inject, Injector, Singleton}
import glokka.Registry
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.Id
import org.matsim.core.controler._
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

import scala.concurrent.duration.FiniteDuration

/**
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
  var persons: Map[Id[Person], Person] = Map()
  var personRefs: Map[Id[Person], ActorRef] = Map()
  var vehicles: Map[Id[Vehicle], Vehicle] = Map()
  var vehicleRefs: Map[Id[Vehicle], ActorRef] = Map()
  var households : Map[Id[Household], Household] = Map()
  var agentRefs: Map[Id[_], ActorRef] = Map()

}

object BeamServices {
  implicit val askTimeout = Timeout(FiniteDuration(5L, TimeUnit.SECONDS))
}
