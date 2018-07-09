package beam.sim

import java.lang.Double
import java.util
import java.util.Random
import java.util.concurrent.{ThreadLocalRandom, TimeUnit}
import java.util.stream.Stream

import akka.actor.Status.Success
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, DeadLetter, Identify, Props, Terminated}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.BeamVehicleFuelLevelUpdate
import beam.agentsim.agents.rideHail.RideHailManager.RideHailAllocationManagerTimeout
import beam.agentsim.agents.rideHail.RideHailManager.NotifyIterationEnds
import beam.agentsim.agents.rideHail.{RideHailSurgePricingManager, RideHailAgent, RideHailManager}
import beam.agentsim.agents.vehicles.BeamVehicleType.{Car, HumanBodyVehicle}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles._
import beam.agentsim.infrastructure.QuadTreeBounds
import beam.agentsim.scheduler.{BeamAgentScheduler, Trigger}
import beam.agentsim.agents.{BeamAgent, InitializeTrigger, Population}
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, StartSchedule}
import beam.router.BeamRouter.InitTransit
import beam.sim.metrics.MetricsSupport
import beam.sim.monitoring.ErrorListener
import beam.utils.{DebugActorWithTimer, DebugLib, Tick}
import com.conveyal.r5.transit.TransportNetwork
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.apache.log4j.Logger
import org.matsim.api.core.v01.population.{Activity, Person, PlanElement}
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.gbl.MatsimRandom
import org.matsim.core.mobsim.framework.Mobsim
import org.matsim.core.utils.misc.Time
import org.matsim.households.Household
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * AgentSim.
  *
  * Created by sfeygin on 2/8/17.
  */
class BeamMobsim @Inject()(val beamServices: BeamServices, val transportNetwork: TransportNetwork, val scenario: Scenario, val eventsManager: EventsManager, val actorSystem: ActorSystem, val rideHailSurgePricingManager:RideHailSurgePricingManager) extends Mobsim with LazyLogging with MetricsSupport {
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  val rideHailAgents: ArrayBuffer[ActorRef] = new ArrayBuffer()
  val rideHailHouseholds: mutable.Set[Id[Household]] = mutable.Set[Id[Household]]()
  var debugActorWithTimerActorRef:ActorRef=_
  var debugActorWithTimerCancellable:Cancellable=_
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


  override def run(): Unit = {
    logger.info("Starting Iteration")
    startMeasuringIteration(beamServices.iterationNumber)
//    val iterationTrace = Kamon.tracer.newContext("iteration", Some("iteration"+beamServices.iterationNumber), Map("it-num"->(""+beamServices.iterationNumber)))
//    Tracer.setCurrentContext(iterationTrace)
    logger.info("Preparing new Iteration (Start)")
    startSegment("iteration-preparation", "mobsim")
//    val iterationPreparation = iterationTrace.startSegment("iteration-preparation", "mobsim", "kamon")
//    var agentsimExecution: Segment = null
//    var agentsimEvents: Segment = null
    if(beamServices.beamConfig.beam.debug.debugEnabled)logger.info(DebugLib.gcAndGetMemoryLogMessage("run.start (after GC): "))
    beamServices.startNewIteration
    eventsManager.initProcessing()
    val iteration = actorSystem.actorOf(Props(new Actor with ActorLogging {
      var runSender: ActorRef = _
      private val errorListener = context.actorOf(ErrorListener.props())
      context.watch(errorListener)



      context.system.eventStream.subscribe(errorListener, classOf[BeamAgent.TerminatedPrematurelyEvent])
      private val scheduler = context.actorOf(Props(classOf[BeamAgentScheduler], beamServices.beamConfig, Time.parseTime(beamServices.beamConfig.matsim.modules.qsim.endTime) , 300.0), "scheduler")
      context.system.eventStream.subscribe(errorListener, classOf[DeadLetter])
      context.watch(scheduler)

      private val envelopeInUTM = beamServices.geo.wgs2Utm(transportNetwork.streetLayer.envelope)
      envelopeInUTM.expandBy(beamServices.beamConfig.beam.spatial.boundingBoxBuffer)

      private val rideHailManager = context.actorOf(RideHailManager.props(beamServices, scheduler, beamServices.beamRouter, envelopeInUTM,rideHailSurgePricingManager), "RideHailManager")
      context.watch(rideHailManager)

      if(beamServices.beamConfig.beam.debug.debugActorTimerIntervalInSec > 0){
        debugActorWithTimerActorRef =   context.actorOf(Props(classOf[DebugActorWithTimer],rideHailManager,scheduler))
        debugActorWithTimerCancellable=prepareMemoryLoggingTimerActor(beamServices.beamConfig.beam.debug.debugActorTimerIntervalInSec,context.system,debugActorWithTimerActorRef)
      }

      private val population = context.actorOf(Population.props(scenario, beamServices, scheduler, transportNetwork, beamServices.beamRouter, rideHailManager, eventsManager), "population")
      context.watch(population)
      Await.result(population ? Identify(0), timeout.duration)

      private val numRideHailAgents = math.round(scenario.getPopulation.getPersons.size * beamServices.beamConfig.beam.agentsim.agents.rideHail.numDriversAsFractionOfPopulation)
      private val rideHailVehicleType = scenario.getVehicles.getVehicleTypes.get(Id.create("1", classOf[VehicleType]))

      val quadTreeBounds: QuadTreeBounds = getQuadTreeBound(scenario.getPopulation.getPersons.values().stream().limit(numRideHailAgents))

      val rand: Random = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)

      scenario.getPopulation.getPersons.values().stream().limit(numRideHailAgents).forEach { person =>
        val personInitialLocation: Coord = person.getSelectedPlan.getPlanElements.iterator().next().asInstanceOf[Activity].getCoord
        val rideInitialLocation: Coord = beamServices.beamConfig.beam.agentsim.agents.rideHail.initialLocation match {
          case RideHailManager.INITIAL_RIDEHAIL_LOCATION_HOME =>
            new Coord(personInitialLocation.getX, personInitialLocation.getY)
          case RideHailManager.INITIAL_RIDEHAIL_LOCATION_UNIFORM_RANDOM =>
            val x = quadTreeBounds.minx + (quadTreeBounds.maxx - quadTreeBounds.minx) * rand.nextDouble()
            val y = quadTreeBounds.miny + (quadTreeBounds.maxy - quadTreeBounds.miny) * rand.nextDouble()
            new Coord(x, y)
          case RideHailManager.INITIAL_RIDEHAIL_LOCATION_ALL_AT_CENTER  =>
            val x = quadTreeBounds.minx + (quadTreeBounds.maxx - quadTreeBounds.minx)/2
            val y = quadTreeBounds.miny + (quadTreeBounds.maxy - quadTreeBounds.miny)/2
            new Coord(x, y)
          case unknown =>
            log.error(s"unknown rideHail.initialLocation $unknown")
            null
        }

        val rideHailName = s"rideHailingAgent-${person.getId}"
        val rideHailId = Id.create(rideHailName, classOf[RideHailAgent])
        val rideHailVehicleId = Id.createVehicleId(s"rideHailingVehicle-person=${person.getId}") // XXXX: for now identifier will just be initial location (assumed unique)
        val rideHailVehicle: Vehicle = VehicleUtils.getFactory.createVehicle(rideHailVehicleId, rideHailVehicleType)
        val rideHailAgentPersonId: Id[RideHailAgent] = Id.createPersonId(rideHailName)
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
        rideHailBeamVehicle.registerResource(rideHailManager)
        rideHailManager ! BeamVehicleFuelLevelUpdate(rideHailBeamVehicle.getId,rideHailBeamVehicle.fuelLevel.get)
        val rideHailAgentProps = RideHailAgent.props(beamServices, scheduler, transportNetwork, eventsManager, rideHailAgentPersonId, rideHailBeamVehicle, rideInitialLocation)
        val rideHailAgentRef: ActorRef = context.actorOf(rideHailAgentProps, rideHailName)
        context.watch(rideHailAgentRef)
        scheduler ! ScheduleTrigger(InitializeTrigger(0.0), rideHailAgentRef)
        rideHailAgents += rideHailAgentRef
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
          0.milliseconds,
          (timeoutInSeconds*1000).milliseconds,
          memoryLoggingTimerActorRef,
          Tick)

        cancellable
      }



      override def receive: PartialFunction[Any, Unit] = {

        case CompletionNotice(_, _) =>
          log.info("Scheduler is finished.")
          endSegment("agentsim-execution", "agentsim")
//          agentsimExecution.finish()
          log.info("Ending Agentsim")
          log.info("Processing Agentsim Events (Start)")
          startSegment("agentsim-events", "agentsim")
//          agentsimEvents = iterationTrace.startSegment("agentsim-events", "agentsim", "kamon")
          cleanupRideHailAgents()
          cleanupVehicle()
          population ! Finish
          val future=rideHailManager.ask(NotifyIterationEnds())
          Await.ready(future, timeout.duration).value
          context.stop(rideHailManager)
          context.stop(scheduler)
          context.stop(errorListener)
          if(beamServices.beamConfig.beam.debug.debugActorTimerIntervalInSec > 0) {
            debugActorWithTimerCancellable.cancel()
            context.stop(debugActorWithTimerActorRef)
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
          endSegment("iteration-preparation", "mobsim")
//          iterationPreparation.finish
          log.info("Preparing new Iteration (End)")
          log.info("Starting Agentsim")
          startSegment("agentsim-execution", "agentsim")
//          agentsimExecution = iterationTrace.startSegment("agentsim-execution", "agentsim", "kamon")
          scheduler ! StartSchedule(beamServices.iterationNumber)
      }

      private def scheduleRideHailManagerTimerMessage(): Unit = {
        val timerTrigger=RideHailAllocationManagerTimeout(0.0)
        val timerMessage=ScheduleTrigger(timerTrigger, rideHailManager)
        scheduler ! timerMessage
        log.info(s"rideHailManagerTimerScheduled")
      }

      private def cleanupRideHailAgents(): Unit = {
        rideHailAgents.foreach(_ ! Finish)
        rideHailAgents.clear()
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
    endSegment("agentsim-events", "agentsim")
//    agentsimEvents.finish()
    logger.info("Processing Agentsim Events (End)")
  }
}



