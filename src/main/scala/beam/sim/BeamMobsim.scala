package beam.sim

import java.lang.Double
import java.util
import java.util.Random
import java.util.concurrent.{ThreadLocalRandom, TimeUnit}
import java.util.stream.Stream

import akka.actor.Status.Success
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, DeadLetter, Identify, PoisonPill, Props, Terminated}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.BeamVehicleFuelLevelUpdate
import beam.agentsim.agents.{BeamAgent, InitializeTrigger, Population}
import beam.agentsim.agents.rideHail.RideHailingManager.{NotifyIterationEnds, RideHailAllocationManagerTimeout}
import beam.agentsim.agents.rideHail.{RideHailSurgePricingManager, RideHailingAgent, RideHailingManager}
import beam.agentsim.agents.vehicles.BeamVehicleType.{Car, HumanBodyVehicle}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles._
import beam.agentsim.infrastructure.QuadTreeBounds
import beam.agentsim.scheduler.{BeamAgentScheduler, Trigger}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, StartSchedule}
import beam.router.BeamRouter.InitTransit
import beam.sim.monitoring.ErrorListener
import beam.utils.{DebugLib, MemoryLoggingTimerActor, Tick}
import com.conveyal.r5.transit.TransportNetwork
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.apache.log4j.Logger
import org.matsim.api.core.v01.population.{Activity, Person, PlanElement}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.gbl.MatsimRandom
import org.matsim.core.mobsim.framework.Mobsim
import org.matsim.households.Household
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}
import org.matsim.core.utils.misc.Time

import scala.concurrent.duration._
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

/**
  * AgentSim.
  *
  * Created by sfeygin on 2/8/17.
  */
class BeamMobsim @Inject()(val beamServices: BeamServices, val transportNetwork: TransportNetwork, val scenario: Scenario, val eventsManager: EventsManager, val actorSystem: ActorSystem, val rideHailSurgePricingManager:RideHailSurgePricingManager) extends Mobsim with LazyLogging {
  private implicit val timeout = Timeout(50000, TimeUnit.SECONDS)

  var rideHailingAgents: Seq[ActorRef] = Nil
  val rideHailingHouseholds: mutable.Set[Id[Household]] = mutable.Set[Id[Household]]()
  var memoryLoggingTimerActorRef:ActorRef=_
  var memoryLoggingTimerCancellable:Cancellable=_
/*
  var rideHailSurgePricingManager: RideHailSurgePricingManager = injector.getInstance(classOf[BeamServices])
  new RideHailSurgePricingManager(beamServices.beamConfig,beamServices.taz);*/

  def getQuadTreeBound[p <: Person](persons: Stream[p]): QuadTreeBounds ={

    var minX: Double = null
    var maxX: Double = null
    var minY: Double = null
    var maxY: Double = null

    persons.forEach { person =>
      val planElementsIterator = person.getSelectedPlan.getPlanElements.iterator()
      while(planElementsIterator.hasNext){
        val planElement = planElementsIterator.next()
        if(planElement.isInstanceOf[Activity]){
          val coord = planElement.asInstanceOf[Activity].getCoord
          minX = if(minX == null || minX > coord.getX) coord.getX else minX
          maxX = if(maxX == null || maxX < coord.getX) coord.getX else maxX
          minY = if(minY == null || minY > coord.getY) coord.getY else minY
          maxY = if(maxY == null || maxY < coord.getY) coord.getY else maxY
        }
      }
    }

    new QuadTreeBounds(minX, minY, maxX, maxY)
  }


  override def run() = {
    if(beamServices.beamConfig.beam.debug.debugEnabled)logger.info(DebugLib.gcAndGetMemoryLogMessage("run.start (after GC): "))
    beamServices.startNewIteration
    eventsManager.initProcessing()
    val iteration = actorSystem.actorOf(Props(new Actor with ActorLogging {
      var runSender: ActorRef = _
      private val errorListener = context.actorOf(ErrorListener.props())
      context.watch(errorListener)

      if(beamServices.beamConfig.beam.debug.memoryConsumptionDisplayTimeoutInSec > 0){
        memoryLoggingTimerActorRef =   context.actorOf(Props(classOf[MemoryLoggingTimerActor]))
        memoryLoggingTimerCancellable=prepareMemoryLoggingTimerActor(beamServices.beamConfig.beam.debug.memoryConsumptionDisplayTimeoutInSec,context.system,memoryLoggingTimerActorRef)
      }

      context.system.eventStream.subscribe(errorListener, classOf[BeamAgent.TerminatedPrematurelyEvent])
      private val scheduler = context.actorOf(Props(classOf[BeamAgentScheduler], beamServices.beamConfig, Time.parseTime(beamServices.beamConfig.matsim.modules.qsim.endTime) , 300.0), "scheduler")
      context.system.eventStream.subscribe(errorListener, classOf[DeadLetter])
      context.watch(scheduler)

      private val envelopeInUTM = beamServices.geo.wgs2Utm(transportNetwork.streetLayer.envelope)
      envelopeInUTM.expandBy(beamServices.beamConfig.beam.spatial.boundingBoxBuffer)

      private val rideHailingManager = context.actorOf(RideHailingManager.props(beamServices, scheduler, beamServices.beamRouter, envelopeInUTM,rideHailSurgePricingManager), "RideHailingManager")
      context.watch(rideHailingManager)
      private val population = context.actorOf(Population.props(scenario, beamServices, scheduler, transportNetwork, beamServices.beamRouter, rideHailingManager, eventsManager), "population")
      context.watch(population)
      Await.result(population ? Identify(0), timeout.duration)

      private val numRideHailAgents = math.round(scenario.getPopulation.getPersons.size * beamServices.beamConfig.beam.agentsim.agents.rideHailing.numDriversAsFractionOfPopulation)
      private val rideHailingVehicleType = scenario.getVehicles.getVehicleTypes.get(Id.create("1", classOf[VehicleType]))

      val quadTreeBounds: QuadTreeBounds = getQuadTreeBound(scenario.getPopulation.getPersons.values().stream().limit(numRideHailAgents))

      val rand: Random = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)

      scenario.getPopulation.getPersons.values().stream().limit(numRideHailAgents).forEach { person =>
        val personInitialLocation: Coord = person.getSelectedPlan.getPlanElements.iterator().next().asInstanceOf[Activity].getCoord
        val rideInitialLocation: Coord = beamServices.beamConfig.beam.agentsim.agents.rideHailing.initialLocation match {
          case RideHailingManager.INITIAL_RIDEHAIL_LOCATION_HOME =>
            new Coord(personInitialLocation.getX, personInitialLocation.getY)
          case RideHailingManager.INITIAL_RIDEHAIL_LOCATION_UNIFORM_RANDOM =>
            val x = quadTreeBounds.minx + (quadTreeBounds.maxx - quadTreeBounds.minx) * rand.nextDouble()
            val y = quadTreeBounds.miny + (quadTreeBounds.maxy - quadTreeBounds.miny) * rand.nextDouble()
            new Coord(x, y)
          case unknown =>
            log.error(s"unknown rideHailing.initialLocation $unknown")
            null
        }

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
        val rideHailBeamVehicle = new BeamVehicle(powerTrain, rideHailVehicle, vehicleAttribute, Car, Some(1.0),
          Some(beamServices.beamConfig.beam.agentsim.tuning.fuelCapacityInJoules))
        beamServices.vehicles += (rideHailVehicleId -> rideHailBeamVehicle)
        rideHailBeamVehicle.registerResource(rideHailingManager)
        rideHailingManager ! BeamVehicleFuelLevelUpdate(rideHailBeamVehicle.getId,rideHailBeamVehicle.fuelLevel.get)
        val rideHailingAgentProps = RideHailingAgent.props(beamServices, scheduler, transportNetwork, eventsManager, rideHailingAgentPersonId, rideHailBeamVehicle, rideInitialLocation)
        val rideHailingAgentRef: ActorRef = context.actorOf(rideHailingAgentProps, rideHailingName)
        context.watch(rideHailingAgentRef)
        scheduler ! ScheduleTrigger(InitializeTrigger(0.0), rideHailingAgentRef)
        rideHailingAgents :+= rideHailingAgentRef
      }

      log.info(s"Initialized ${beamServices.personRefs.size} people")
      log.info(s"Initialized ${scenario.getVehicles.getVehicles.size()} personal vehicles")
      log.info(s"Initialized ${numRideHailAgents} ride hailing agents")
      Await.result(beamServices.beamRouter ? InitTransit(scheduler), timeout.duration)
      log.info(s"Transit schedule has been initialized")

      scheduleRideHailManagerTimerMessage()


      def prepareMemoryLoggingTimerActor(timeoutInSeconds:Int,system:ActorSystem,memoryLoggingTimerActorRef: ActorRef):Cancellable={
        import system.dispatcher

        val cancellable=system.scheduler.schedule(
          0 milliseconds,
          timeoutInSeconds*1000 milliseconds,
          memoryLoggingTimerActorRef,
          Tick)

        cancellable
      }



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
          if(beamServices.beamConfig.beam.debug.memoryConsumptionDisplayTimeoutInSec > 0) {
            memoryLoggingTimerCancellable.cancel()
            context.stop(memoryLoggingTimerActorRef)
          }

        case Terminated(_) =>
          if (context.children.isEmpty) {
            context.stop(self)
            runSender ! Success("Ran.")
          } else {
            log.debug(s"Remaining: ${context.children}")
          }

        case "Run!" =>
          runSender = sender
          log.info("Running BEAM Mobsim")
          scheduler ! StartSchedule(beamServices.iterationNumber)
      }

      private def scheduleRideHailManagerTimerMessage(): Unit = {
        val timerTrigger=RideHailAllocationManagerTimeout(0.0)
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

    }),"BeamMobsim.iteration")
    Await.result(iteration ? "Run!", timeout.duration)
    logger.info("Agentsim finished.")
    eventsManager.finishProcessing()
    logger.info("Events drained.")
  }
}



