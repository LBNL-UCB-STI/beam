package beam.sim

import java.util.concurrent.TimeUnit

import akka.actor.Status.Success
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, DeadLetter, Identify, PoisonPill, Props, Terminated}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.RideHailingManager.{NotifyIterationEnds, RepositioningTimer}
import beam.agentsim.agents.{RideHailSurgePricingManager, _}
import beam.agentsim.agents.vehicles.BeamVehicleType.{Car, HumanBodyVehicle}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles._
import beam.agentsim.scheduler.{BeamAgentScheduler, Trigger}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, StartSchedule}
import beam.router.BeamRouter.InitTransit
import beam.sim.monitoring.ErrorListener
import com.conveyal.r5.transit.TransportNetwork
import com.google.inject.Inject
import org.apache.log4j.Logger
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.mobsim.framework.Mobsim
import org.matsim.households.Household
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}
import org.matsim.core.utils.misc.Time

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

/**
  * AgentSim.
  *
  * Created by sfeygin on 2/8/17.
  */
class BeamMobsim @Inject()(val beamServices: BeamServices, val transportNetwork: TransportNetwork, val scenario: Scenario, val eventsManager: EventsManager, val actorSystem: ActorSystem, val rideHailSurgePricingManager:RideHailSurgePricingManager) extends Mobsim {
  private implicit val timeout = Timeout(50000, TimeUnit.SECONDS)

  private val log = Logger.getLogger(classOf[BeamMobsim])

  var rideHailingAgents: Seq[ActorRef] = Nil
  val rideHailingHouseholds: mutable.Set[Id[Household]] = mutable.Set[Id[Household]]()
/*
  var rideHailSurgePricingManager: RideHailSurgePricingManager = injector.getInstance(classOf[BeamServices])
  new RideHailSurgePricingManager(beamServices.beamConfig,beamServices.taz);*/

  override def run() = {
    beamServices.clearAll
    eventsManager.initProcessing()
    val iteration = actorSystem.actorOf(Props(new Actor with ActorLogging {
      var runSender: ActorRef = _
      private val errorListener = context.actorOf(ErrorListener.props())
      context.watch(errorListener)
      context.system.eventStream.subscribe(errorListener, classOf[BeamAgent.TerminatedPrematurelyEvent])
      val scheduler = context.actorOf(Props(classOf[BeamAgentScheduler], beamServices.beamConfig, Time.parseTime(beamServices.beamConfig.matsim.modules.qsim.endTime) , 300.0), "scheduler")
      context.system.eventStream.subscribe(errorListener, classOf[DeadLetter])
      context.watch(scheduler)
      beamServices.schedulerRef=scheduler

      private val envelopeInUTM = beamServices.geo.wgs2Utm(transportNetwork.streetLayer.envelope)
      envelopeInUTM.expandBy(beamServices.beamConfig.beam.spatial.boundingBoxBuffer)

      private val rideHailingManager = context.actorOf(RideHailingManager.props("RideHailingManager", beamServices, beamServices.beamRouter, envelopeInUTM,rideHailSurgePricingManager),RideHailingManager.RIDE_HAIL_MANAGER)
      context.watch(rideHailingManager)
      private val population = context.actorOf(Population.props(scenario, beamServices, scheduler, transportNetwork, beamServices.beamRouter, rideHailingManager, eventsManager), "population")
      context.watch(population)
      Await.result(population ? Identify(0), timeout.duration)

      private val numRideHailAgents = math.round(scenario.getPopulation.getPersons.size * beamServices.beamConfig.beam.agentsim.agents.rideHailing.numDriversAsFractionOfPopulation)
      private val rideHailingVehicleType = scenario.getVehicles.getVehicleTypes.get(Id.create("1", classOf[VehicleType]))

      scenario.getPopulation.getPersons.values().stream().limit(numRideHailAgents).forEach { person =>
        val personInitialLocation: Coord = person.getSelectedPlan.getPlanElements.iterator().next().asInstanceOf[Activity].getCoord
        val rideInitialLocation: Coord = new Coord(personInitialLocation.getX, personInitialLocation.getY)
        val rideHailingName = s"rideHailingAgent-${person.getId}"
        val rideHailId = Id.create(rideHailingName, classOf[RideHailingAgent])
        val rideHailVehicleId = Id.createVehicleId(s"rideHailingVehicle-person=${person.getId}") // XXXX: for now identifier will just be initial location (assumed unique)
        val rideHailVehicle: Vehicle = VehicleUtils.getFactory.createVehicle(rideHailVehicleId, rideHailingVehicleType)
        val rideHailingAgentPersonId: Id[RideHailingAgent] = Id.createPersonId(rideHailingName)
        val information = Option(rideHailVehicle.getType.getEngineInformation)
        val vehicleAttribute = Option(
          scenario.getVehicles.getVehicleAttributes)
        val powerTrain = Powertrain.PowertrainFromMilesPerGallon(
          information
            .map(_.getGasConsumption)
            .getOrElse(Powertrain.AverageMilesPerGallon))
        val rideHailBeamVehicle = new BeamVehicle(powerTrain, rideHailVehicle, vehicleAttribute, Car)
        beamServices.vehicles += (rideHailVehicleId -> rideHailBeamVehicle)
        rideHailBeamVehicle.registerResource(rideHailingManager)
        val rideHailingAgentProps = RideHailingAgent.props(beamServices, scheduler, transportNetwork, eventsManager, rideHailingAgentPersonId, rideHailBeamVehicle, rideInitialLocation)
        val rideHailingAgentRef: ActorRef = context.actorOf(rideHailingAgentProps, rideHailingName)
        context.watch(rideHailingAgentRef)
        beamServices.agentRefs.put(rideHailingName, rideHailingAgentRef)
        scheduler ! ScheduleTrigger(InitializeTrigger(0.0), rideHailingAgentRef)
        rideHailingAgents :+= rideHailingAgentRef
      }

      log.info(s"Initialized ${beamServices.personRefs.size} people")
      log.info(s"Initialized ${scenario.getVehicles.getVehicles.size()} personal vehicles")
      log.info(s"Initialized ${numRideHailAgents} ride hailing agents")
      Await.result(beamServices.beamRouter ? InitTransit(scheduler), timeout.duration)
      log.info(s"Transit schedule has been initialized")

      scheduleRideHailManagerTimerMessage()


      override def receive = {

        case CompletionNotice(_, _) =>
          log.debug("Scheduler is finished.")
          cleanupRideHailingAgents()
          cleanupVehicle()
          population ! Finish
          val future=rideHailingManager.ask(NotifyIterationEnds())
          Await.ready(future, timeout.duration).value
          context.stop(rideHailingManager)
          context.stop(scheduler)
          context.stop(errorListener)

        case Terminated(_) =>
          if (context.children.isEmpty) {
            context.stop(self)
            runSender ! Success("Ran.")
          } else {
            log.debug("Remaining: {}", context.children)
          }

        case "Run!" =>
          runSender = sender
          log.info("Running BEAM Mobsim")
          scheduler ! StartSchedule(0)
      }

      private def scheduleRideHailManagerTimerMessage(): Unit = {
        val timerTrigger=RepositioningTimer(0.0)
        val timerMessage=ScheduleTrigger(timerTrigger, rideHailingManager)
        scheduler ! timerMessage
        log.info(s"rideHailManagerTimerScheduled")
      }

      private def cleanupRideHailingAgents(): Unit = {
        rideHailingAgents.foreach(_ ! Finish)
        rideHailingAgents = Nil
      }

      private def cleanupVehicle(): Unit = {
        // FIXME XXXX (VR): Probably no longer necessarylog.info(s"Removing Humanbody vehicles")
        scenario.getPopulation.getPersons.keySet().forEach { personId =>
          val bodyVehicleId = HumanBodyVehicle.createId(personId)
          beamServices.vehicles -= bodyVehicleId
        }
      }

    }))
    Await.result(iteration ? "Run!", timeout.duration)
    log.info("Agentsim finished.")
    eventsManager.finishProcessing()
    log.info("Events drained.")
  }
}




