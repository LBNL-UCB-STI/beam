package beam.sim

import java.util.concurrent.{ConcurrentHashMap, ConcurrentMap, TimeUnit}

import akka.actor.{ActorRef, ActorSystem}
import akka.util.Timeout
import beam.agentsim.agents.TransitDriverAgent
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.sim.config.BeamConfig
import beam.agentsim.events.AgentsimEventsBus
import beam.router.RoutingModel.BeamLeg
import beam.sim.akkaguice.ActorInject
import beam.sim.common.GeoUtils
import com.google.inject.{ImplementedBy, Inject, Injector, Singleton}
import glokka.Registry
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.Id
import org.matsim.core.controler._
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/**
  */

@ImplementedBy(classOf[BeamServicesImpl])
trait BeamServices extends ActorInject {
  val matsimServices: MatsimServices
  val controler: ControlerI
  var beamConfig: BeamConfig
  val agentSimEventsBus: AgentsimEventsBus

  val registry: ActorRef
  val geo: GeoUtils
  var modeChoiceCalculator: ModeChoiceCalculator

  var beamRouter: ActorRef
  var physSim: ActorRef
  var schedulerRef: ActorRef
  var rideHailingManager: ActorRef
  val persons: collection.concurrent.Map[Id[Person], Person]
  val personRefs: collection.concurrent.Map[Id[Person], ActorRef]
  val vehicles: collection.concurrent.Map[Id[Vehicle], Vehicle]
  val vehicleRefs: collection.concurrent.Map[Id[Vehicle], ActorRef]
  val households: collection.concurrent.Map[Id[Household], Household]
  val householdRefs: collection.concurrent.Map[Id[Household], ActorRef]
  val agentRefs: collection.concurrent.Map[String, ActorRef]
  val transitVehiclesByBeamLeg: mutable.Map[BeamLeg, Id[Vehicle]]
  val transitDriversByVehicle: mutable.Map[Id[Vehicle], Id[TransitDriverAgent]]
}

class BeamServicesImpl @Inject()(val injector: Injector) extends BeamServices{
  val matsimServices: MatsimServices = injector.getInstance(classOf[MatsimServices])
  val controler: ControlerI = injector.getInstance(classOf[ControlerI])
  var beamConfig: BeamConfig = injector.getInstance(classOf[BeamConfig])
  val agentSimEventsBus = new AgentsimEventsBus
  val registry: ActorRef = Registry.start(injector.getInstance(classOf[ActorSystem]), "actor-registry")

  val geo: GeoUtils = injector.getInstance(classOf[GeoUtils])

  //TODO find a better way to inject these, for now they are initilized inside BeamSim.notifyStartup
  var modeChoiceCalculator: ModeChoiceCalculator = _
  var beamRouter: ActorRef = _
  var physSim: ActorRef = _
  var schedulerRef: ActorRef = _
  var rideHailingManager: ActorRef = _
  val persons: collection.concurrent.Map[Id[Person], Person] = collection.concurrent.TrieMap[Id[Person], Person]()
  val personRefs: collection.concurrent.Map[Id[Person], ActorRef] = collection.concurrent.TrieMap[Id[Person], ActorRef]()
  val vehicles: collection.concurrent.Map[Id[Vehicle], Vehicle] = collection.concurrent.TrieMap[Id[Vehicle], Vehicle]()
  val vehicleRefs: collection.concurrent.Map[Id[Vehicle], ActorRef] = collection.concurrent.TrieMap[Id[Vehicle], ActorRef]()
  val households: collection.concurrent.Map[Id[Household], Household] = collection.concurrent.TrieMap[Id[Household], Household]()
  val householdRefs: collection.concurrent.Map[Id[Household], ActorRef] = collection.concurrent.TrieMap[Id[Household], ActorRef]()
  val agentRefs: collection.concurrent.Map[String, ActorRef] = collection.concurrent.TrieMap[String, ActorRef]()
  val transitVehiclesByBeamLeg: mutable.Map[BeamLeg, Id[Vehicle]] = collection.concurrent.TrieMap[BeamLeg, Id[Vehicle]]()
  val transitDriversByVehicle: mutable.Map[Id[Vehicle], Id[TransitDriverAgent]] = collection.concurrent.TrieMap[Id[Vehicle], Id[TransitDriverAgent]]()
}

object BeamServices {
  implicit val askTimeout: Timeout = Timeout(FiniteDuration(5L, TimeUnit.SECONDS))
}
