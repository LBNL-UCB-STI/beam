package beam.sim

import java.time.ZonedDateTime
import java.util.concurrent.{ConcurrentHashMap, ConcurrentMap, TimeUnit}

import akka.actor.{ActorRef, ActorSystem}
import akka.util.Timeout
import beam.agentsim.agents.TransitDriverAgent
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.sim.config.BeamConfig
import beam.agentsim.events.AgentsimEventsBus
import beam.router.RoutingModel.{BeamLeg, BeamLegWithNext}
import beam.sim.akkaguice.ActorInject
import beam.sim.common.{DateUtils, GeoUtils}
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
import scala.collection.concurrent.TrieMap

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
  val dates: DateUtils

  var beamRouter: ActorRef
  var physSim: ActorRef
  var schedulerRef: ActorRef
  var rideHailingManager: ActorRef
  val persons: TrieMap[Id[Person], Person]
  val personRefs: TrieMap[Id[Person], ActorRef]
  val vehicles: TrieMap[Id[Vehicle], Vehicle]
  val vehicleRefs: TrieMap[Id[Vehicle], ActorRef]
  val households: TrieMap[Id[Household], Household]
  val householdRefs: TrieMap[Id[Household], ActorRef]
  val agentRefs: TrieMap[String, ActorRef]
  val transitVehiclesByBeamLeg: TrieMap[BeamLeg, Id[Vehicle]]
  val transitDriversByVehicle: TrieMap[Id[Vehicle], Id[TransitDriverAgent]]
  //TODO refactor this into named case clases
  val transitLegsByStopAndDeparture: TrieMap[Tuple3[String, String, Long],BeamLegWithNext]
}

class BeamServicesImpl @Inject()(val injector: Injector) extends BeamServices{
  val matsimServices: MatsimServices = injector.getInstance(classOf[MatsimServices])
  val controler: ControlerI = injector.getInstance(classOf[ControlerI])
  var beamConfig: BeamConfig = injector.getInstance(classOf[BeamConfig])
  val agentSimEventsBus = new AgentsimEventsBus
  val registry: ActorRef = Registry.start(injector.getInstance(classOf[ActorSystem]), "actor-registry")

  val geo: GeoUtils = injector.getInstance(classOf[GeoUtils])
  val dates: DateUtils = DateUtils(beamConfig.beam.routing.baseDate,ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,ZonedDateTime.parse(beamConfig.beam.routing.baseDate))

  var modeChoiceCalculator: ModeChoiceCalculator = _
  var beamRouter: ActorRef = _
  var physSim: ActorRef = _
  var schedulerRef: ActorRef = _
  var rideHailingManager: ActorRef = _
  val persons: TrieMap[Id[Person], Person] = TrieMap[Id[Person], Person]()
  val personRefs: TrieMap[Id[Person], ActorRef] = TrieMap[Id[Person], ActorRef]()
  val vehicles: TrieMap[Id[Vehicle], Vehicle] = TrieMap[Id[Vehicle], Vehicle]()
  val vehicleRefs: TrieMap[Id[Vehicle], ActorRef] = TrieMap[Id[Vehicle], ActorRef]()
  val households: TrieMap[Id[Household], Household] = TrieMap[Id[Household], Household]()
  val householdRefs: TrieMap[Id[Household], ActorRef] = TrieMap[Id[Household], ActorRef]()
  val agentRefs: TrieMap[String, ActorRef] = TrieMap[String, ActorRef]()
  val transitVehiclesByBeamLeg: TrieMap[BeamLeg, Id[Vehicle]] = TrieMap[BeamLeg, Id[Vehicle]]()
  val transitDriversByVehicle: TrieMap[Id[Vehicle], Id[TransitDriverAgent]] = TrieMap[Id[Vehicle], Id[TransitDriverAgent]]()
  val transitLegsByStopAndDeparture: TrieMap[Tuple3[String, String, Long],BeamLegWithNext] = TrieMap[Tuple3[String, String, Long],BeamLegWithNext]()
}

object BeamServices {
  implicit val askTimeout: Timeout = Timeout(FiniteDuration(5L, TimeUnit.SECONDS))
}
