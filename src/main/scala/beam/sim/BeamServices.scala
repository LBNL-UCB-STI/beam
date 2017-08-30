package beam.sim

import java.util.concurrent.{ConcurrentHashMap, ConcurrentMap, TimeUnit}

import akka.actor.{ActorRef, ActorSystem}
import akka.util.Timeout
import beam.agentsim.agents.TransitDriverAgent
import beam.playground.akkaguice.ActorInject
import beam.sim.config.BeamConfig
import beam.agentsim.events.AgentsimEventsBus
import beam.router.RoutingModel.BeamLeg
import com.google.inject.{Inject, Injector, Singleton}
import glokka.Registry
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.Id
import org.matsim.core.controler._
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.mutable
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
  var rideHailingManager: ActorRef = _
  val persons: collection.concurrent.Map[Id[Person], Person] = collection.concurrent.TrieMap[Id[Person], Person]()
  val personRefs: collection.concurrent.Map[Id[Person], ActorRef] = collection.concurrent.TrieMap[Id[Person], ActorRef]()
  val vehicles: collection.concurrent.Map[Id[Vehicle], Vehicle] = collection.concurrent.TrieMap[Id[Vehicle], Vehicle]()
  val vehicleRefs: collection.concurrent.Map[Id[Vehicle], ActorRef] = collection.concurrent.TrieMap[Id[Vehicle], ActorRef]()
  val households : collection.concurrent.Map[Id[Household], Household] = collection.concurrent.TrieMap[Id[Household], Household]()
  val householdRefs : collection.concurrent.Map[Id[Household], ActorRef] = collection.concurrent.TrieMap[Id[Household], ActorRef]()
  val agentRefs: collection.concurrent.Map[String, ActorRef] = collection.concurrent.TrieMap[String, ActorRef]()
  val transitVehiclesByBeamLeg: mutable.Map[BeamLeg, Id[Vehicle]] = mutable.Map[BeamLeg, Id[Vehicle]]()
  val transitDriversByVehicle: mutable.Map[Id[Vehicle],Id[TransitDriverAgent]] = mutable.Map[Id[Vehicle],Id[TransitDriverAgent]]()

}

object BeamServices {
  implicit val askTimeout = Timeout(FiniteDuration(5L, TimeUnit.SECONDS))
}
