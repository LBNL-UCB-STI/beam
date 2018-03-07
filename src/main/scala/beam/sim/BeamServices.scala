package beam.sim

import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.util.Timeout
import beam.agentsim.agents.household.HouseholdActor.AttributesOfIndividual
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.TAZTreeMap
import beam.sim.akkaguice.ActorInject
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.DateUtils
import com.google.inject.{ImplementedBy, Inject, Injector}
import glokka.Registry
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.core.controler._
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration

/**
  */

@ImplementedBy(classOf[BeamServicesImpl])
trait BeamServices extends ActorInject {
  val controler: ControlerI
  var beamConfig: BeamConfig
//  val matsimServices: MatsimServices

  val registry: ActorRef

  val geo: GeoUtils
  var modeChoiceCalculatorFactory: AttributesOfIndividual => ModeChoiceCalculator
  val dates: DateUtils

  var beamRouter: ActorRef
  var physSim: ActorRef
  var schedulerRef: ActorRef
  var rideHailingManager: ActorRef
  val personRefs: TrieMap[Id[Person], ActorRef]
  val vehicles: TrieMap[Id[Vehicle], BeamVehicle]
  val agentRefs: TrieMap[String, ActorRef]
 // var taz: TAZTreeMap

  def clearAll
}

class BeamServicesImpl @Inject()(val injector: Injector) extends BeamServices {
  val controler: ControlerI = injector.getInstance(classOf[ControlerI])
  var beamConfig: BeamConfig = injector.getInstance(classOf[BeamConfig])
  val registry: ActorRef = Registry.start(injector.getInstance(classOf[ActorSystem]), "actor-registry")
//  val matsimServices: MatsimServices = injector.getInstance(classOf[MatsimServices])

  val geo: GeoUtils = injector.getInstance(classOf[GeoUtils])
  val dates: DateUtils = DateUtils(ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime, ZonedDateTime.parse(beamConfig.beam.routing.baseDate))

  var modeChoiceCalculatorFactory: AttributesOfIndividual => ModeChoiceCalculator = _
  var beamRouter: ActorRef = _
  var physSim: ActorRef = _
  var schedulerRef: ActorRef = _
  var rideHailingManager: ActorRef = _
  val personRefs: TrieMap[Id[Person], ActorRef] = TrieMap[Id[Person], ActorRef]()
  val vehicles: TrieMap[Id[Vehicle], BeamVehicle] = TrieMap[Id[Vehicle], BeamVehicle]()
  val agentRefs: TrieMap[String, ActorRef] = TrieMap[String, ActorRef]()

 // var taz: TAZTreeMap = _

  def clearAll = {
    personRefs.clear
    vehicles.clear()
    agentRefs.clear()
  }
}

object BeamServices {
  implicit val askTimeout: Timeout = Timeout(FiniteDuration(5L, TimeUnit.SECONDS))
}
