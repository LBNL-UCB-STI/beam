package beam.sim

import java.util.concurrent.{ConcurrentHashMap, ConcurrentMap, TimeUnit}

import akka.actor.{ActorRef, ActorSystem}
import akka.util.Timeout
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
  var taxiManager: ActorRef = _
  val persons: mutable.Map[Id[Person], Person] = new ConcurrentHashMap[Id[Person], Person]().asScala
  val personRefs: mutable.Map[Id[Person], ActorRef] = new ConcurrentHashMap[Id[Person], ActorRef]().asScala
  val vehicles: mutable.Map[Id[Vehicle], Vehicle] =new ConcurrentHashMap[Id[Vehicle], Vehicle]().asScala
  val vehicleRefs: mutable.Map[Id[Vehicle], ActorRef] =new ConcurrentHashMap[Id[Vehicle], ActorRef]().asScala
  val households : mutable.Map[Id[Household], Household] = new ConcurrentHashMap[Id[Household], Household]().asScala
  val householdRefs : mutable.Map[Id[Household], ActorRef] = new ConcurrentHashMap[Id[Household], ActorRef]().asScala
  val agentRefs: mutable.Map[String, ActorRef] = new ConcurrentHashMap[String, ActorRef]().asScala
  val transitVehiclesByBeamLeg: mutable.Map[BeamLeg, Id[Vehicle]] = mutable.Map[BeamLeg, Id[Vehicle]]()

}

object BeamServices {
  implicit val askTimeout = Timeout(FiniteDuration(5L, TimeUnit.SECONDS))
}
