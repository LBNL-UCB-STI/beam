package beam.sim

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Identify, PoisonPill, Props}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.Resource.AssignManager
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents._
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.BeamVehicle.BeamVehicleIdAndRef
import beam.agentsim.agents.vehicles._
import beam.agentsim.agents.vehicles.household.HouseholdActor
import beam.agentsim.events._
import beam.agentsim.events.handling.BeamEventsLogger
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.physsim.jdeqsim.AgentSimToPhysSimPlanConverter
import beam.router.BeamRouter
import beam.router.BeamRouter.InitTransit
import beam.router.gtfs.FareCalculator
import beam.sim.config.BeamLoggingSetup
import beam.sim.monitoring.ErrorListener
import com.google.inject.Inject
import glokka.Registry
import glokka.Registry.Created
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.{AgentWaitingForPtEvent, EventsManager, TeleportationArrivalEvent}
import org.matsim.core.controler.events.{IterationEndsEvent, IterationStartsEvent, ShutdownEvent, StartupEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, IterationStartsListener, ShutdownListener, StartupListener}
import org.matsim.vehicles.{Vehicle, VehicleCapacity, VehicleType, VehicleUtils}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.Await
import scala.util.Random

/**
  * AgentSim entrypoint.
  * Should instantiate the [[ActorSystem]], [[BeamServices]] and interact concurrently w/ the QSim.
  *
  * Created by sfeygin on 2/8/17.
  */
class BeamSim @Inject()(private val actorSystem: ActorSystem,
                        private val beamServices: BeamServices
                       ) extends StartupListener with IterationStartsListener with IterationEndsListener with ShutdownListener {

  private val logger: Logger = LoggerFactory.getLogger(classOf[BeamSim])
  var eventSubscriber: ActorRef = _
  var eventsManager: EventsManager = _
  var writer: BeamEventsLogger = _
  var currentIter = 0
  var agentSimToPhysSimPlanConverter: AgentSimToPhysSimPlanConverter = new AgentSimToPhysSimPlanConverter(beamServices)
  var rideHailingAgents: Seq[ActorRef] = Nil

  private implicit val timeout = Timeout(50000, TimeUnit.SECONDS)

  override def notifyStartup(event: StartupEvent): Unit = {
    actorSystem.eventStream.setLogLevel(BeamLoggingSetup.log4jLogLevelToAkka(beamServices.beamConfig.beam.outputs.logging.beam.logLevel))
    eventsManager = beamServices.matsimServices.getEvents
    eventSubscriber = actorSystem.actorOf(Props(classOf[EventsSubscriber], eventsManager), EventsSubscriber.SUBSCRIBER_NAME)

    subscribe(ActivityEndEvent.EVENT_TYPE)
    subscribe(ActivityStartEvent.EVENT_TYPE)
    subscribe(PersonEntersVehicleEvent.EVENT_TYPE)
    subscribe(PersonLeavesVehicleEvent.EVENT_TYPE)
    subscribe(VehicleEntersTrafficEvent.EVENT_TYPE)
    subscribe(PathTraversalEvent.EVENT_TYPE)
    subscribe(VehicleLeavesTrafficEvent.EVENT_TYPE)
    subscribe(PersonDepartureEvent.EVENT_TYPE)
    subscribe(AgentWaitingForPtEvent.EVENT_TYPE)
    subscribe(TeleportationArrivalEvent.EVENT_TYPE)
    subscribe(PersonArrivalEvent.EVENT_TYPE)
    subscribe(PointProcessEvent.EVENT_TYPE)
    subscribe(ModeChoiceEvent.EVENT_TYPE)

    beamServices.modeChoiceCalculator = ModeChoiceCalculator(beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass, beamServices)

    val schedulerFuture = beamServices.registry ? Registry.Register("scheduler", Props(classOf[BeamAgentScheduler], beamServices.beamConfig, 3600 * 30.0, 300.0))
    beamServices.schedulerRef = Await.result(schedulerFuture, timeout.duration).asInstanceOf[Created].ref

    // Before we initialize router we need to scale the transit vehicle capacities
    val alreadyScaled: mutable.HashSet[VehicleCapacity] = mutable.HashSet()
    beamServices.matsimServices.getScenario.getTransitVehicles.getVehicleTypes.asScala.foreach{ case(typeId, vehType) =>
      val theCap: VehicleCapacity = vehType.getCapacity
      if(!alreadyScaled.contains(theCap)){
        theCap.setSeats(math.round(theCap.getSeats * beamServices.beamConfig.beam.agentsim.tuning.transitCapacity).toInt)
        theCap.setStandingRoom(math.round(theCap.getStandingRoom * beamServices.beamConfig.beam.agentsim.tuning.transitCapacity).toInt)
        alreadyScaled.add(theCap)
      }
    }

    val fareCalculator = new FareCalculator(beamServices.beamConfig.beam.routing.r5.directory)

    val router = actorSystem.actorOf(BeamRouter.props(beamServices, beamServices.matsimServices.getScenario.getTransitVehicles, fareCalculator), "router")
    beamServices.beamRouter = router
    Await.result(beamServices.beamRouter ? Identify(0), timeout.duration)

    val rideHailingManagerFuture = beamServices.registry ? Registry.Register("RideHailingManager", RideHailingManager.props("RideHailingManager",
      Map[Id[VehicleType], BigDecimal](), beamServices.vehicles.toMap, beamServices, Map.empty))
    beamServices.rideHailingManager = Await.result(rideHailingManagerFuture, timeout.duration).asInstanceOf[Created].ref


  }

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
    currentIter = event.getIteration
    resetPop(event.getIteration)
    Await.result(beamServices.beamRouter ? InitTransit, timeout.duration)
    logger.info(s"Transit schedule has been initialized")
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    cleanupRideHailingAgents()
    cleanupVehicle()
    cleanupHouseHolder()
    agentSimToPhysSimPlanConverter.startPhysSim()
  }

  private def cleanupRideHailingAgents(): Unit = {
    rideHailingAgents.foreach(_ ! Finish)
    rideHailingAgents = Nil
  }

  private def cleanupVehicle(): Unit = {
    logger.info(s"Stopping  BeamVehicle actors")
    for ((_, actorRef) <- beamServices.vehicleRefs) {
      actorRef ! Finish

    }
    for (personId <- beamServices.persons.keys) {
      val bodyVehicleId = HumanBodyVehicle.createId(personId)
      beamServices.vehicles -= bodyVehicleId
    }
  }

  private def cleanupHouseHolder(): Unit = {
    for ((_, householdAgent) <- beamServices.householdRefs) {
      logger.debug(s"Stopping ${householdAgent.path.name} ")
      householdAgent ! PoisonPill
    }
  }

  override def notifyShutdown(event: ShutdownEvent): Unit = {
    actorSystem.stop(eventSubscriber)
    actorSystem.stop(beamServices.schedulerRef)
    actorSystem.terminate()
  }

  def resetPop(iter: Int): Unit = {

    val random = new Random(beamServices.matsimServices.getConfig.global().getRandomSeed)

    val errorListener = actorSystem.actorOf(ErrorListener.props(iter))
    actorSystem.eventStream.subscribe(errorListener, classOf[BeamAgent.TerminatedPrematurelyEvent])

    beamServices.persons ++= scala.collection.JavaConverters.mapAsScalaMap(beamServices.matsimServices.getScenario.getPopulation.getPersons)
    beamServices.vehicles ++= beamServices.matsimServices.getScenario.getVehicles.getVehicles.asScala.toMap
    beamServices.households ++= beamServices.matsimServices.getScenario.getHouseholds.getHouseholds.asScala.toMap
    logger.info(s"Loaded ${beamServices.persons.size} people in ${beamServices.households.size} households with ${beamServices.vehicles.size} vehicles")

    val iterId = Option(iter.toString)

    beamServices.vehicleRefs ++= initVehicleActors(iterId)

    // FIXME: Must wait for population because it currently initializes global variables
    val population = actorSystem.actorOf(Population.props(beamServices), "population")
    Await.result(population ? Identify(0), timeout.duration)

    //TODO the following should be based on config params
    //    val numRideHailAgents = 0.1
    val numRideHailAgents = math.round(math.min(beamServices.beamConfig.beam.agentsim.numAgents,beamServices.persons.size) * beamServices.beamConfig.beam.agentsim.agents.rideHailing.numDriversAsFractionOfPopulation).toInt
    val initialLocationJitter = 500 // meters

    val rideHailingVehicleType = beamServices.matsimServices.getScenario.getVehicles.getVehicleTypes().get(Id.create("1",classOf[VehicleType]))

    var rideHailingVehicles: Map[Id[Vehicle], ActorRef] = Map[Id[Vehicle], ActorRef]()

    for ((k, v) <- beamServices.persons.take(numRideHailAgents)) {
      val personInitialLocation: Coord = v.getSelectedPlan.getPlanElements.iterator().next().asInstanceOf[Activity].getCoord
      //      val rideInitialLocation: Coord = new Coord(personInitialLocation.getX + initialLocationJitter * 2.0 * (1 - 0.5), personInitialLocation.getY + initialLocationJitter * 2.0 * (1 - 0.5))
      val rideInitialLocation: Coord = new Coord(personInitialLocation.getX, personInitialLocation.getY)
      val rideHailingName = s"rideHailingAgent-${k}_$iter"
      val rideHailId = Id.create(rideHailingName, classOf[RideHailingAgent])
      val rideHailVehicleId = Id.createVehicleId(s"rideHailingVehicle-person=$k") // XXXX: for now identifier will just be initial location (assumed unique)
      val rideHailVehicle: Vehicle = VehicleUtils.getFactory.createVehicle(rideHailVehicleId, rideHailingVehicleType)
      val vehicleIdAndRef: (Id[Vehicle], ActorRef) = initCarVehicle(rideHailVehicleId, rideHailVehicle)
      val rideHailingAgent = RideHailingAgent.props(beamServices, rideHailId, BeamVehicleIdAndRef(vehicleIdAndRef), rideInitialLocation)
      val rideHailingAgentRef: ActorRef = actorSystem.actorOf(rideHailingAgent, rideHailingName)

      // populate maps and initialize agent via scheduler
      beamServices.vehicles += (rideHailVehicleId -> rideHailVehicle)
      beamServices.vehicleRefs += vehicleIdAndRef
      beamServices.agentRefs.put(rideHailingName, rideHailingAgentRef)
      vehicleIdAndRef._2 ! AssignManager(beamServices.rideHailingManager)
      beamServices.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), vehicleIdAndRef._2)
      beamServices.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), rideHailingAgentRef)

      rideHailingVehicles += (rideHailVehicleId -> vehicleIdAndRef._2)
      rideHailingAgents :+= rideHailingAgentRef
    }

    logger.info(s"Initialized ${numRideHailAgents} ride hailing agents")

    initHouseholds(iterId)

    //TODO if we can't do the following with generic Ids, then we should seriously consider abandoning typed IDs
    beamServices.personRefs.foreach { case (id, person) =>
      beamServices.agentRefs.put(id.toString, person)
    }
  }

  private def initHouseholds(iterId: Option[String] = None): Unit = {
    val householdAttrs = beamServices.matsimServices.getScenario.getHouseholds.getHouseholdAttributes

    beamServices.households.foreach {
      case (householdId, matSimHousehold) =>
        //TODO a good example where projection should accompany the data
        if(householdAttrs.getAttribute(householdId.toString, "homecoordx") == null){
          logger.error(s"Cannot find homeCoordX for household ${householdId} which will be intepreted at 0.0")
        }
        if(householdAttrs.getAttribute(householdId.toString.toLowerCase(), "homecoordy") == null){
          logger.error(s"Cannot find homeCoordY for household ${householdId} which will be intepreted at 0.0")
        }
        val homeCoord = new Coord(householdAttrs.getAttribute(householdId.toString, "homecoordx").asInstanceOf[Double],
          householdAttrs.getAttribute(householdId.toString, "homecoordy").asInstanceOf[Double])
        val houseHoldVehicles = matSimHousehold.getVehicleIds.asScala.map {
          vehicleId =>
            val vehicleActRef = beamServices.vehicleRefs.get(vehicleId)
            (vehicleId, vehicleActRef)
        }.collect {
          case (vehicleId, Some(vehicleAgent)) =>
            (vehicleId, vehicleAgent)
        }.toMap
        val membersActors = matSimHousehold.getMemberIds.asScala.map {
          personId => (personId, beamServices.personRefs.get(personId))
        }.collect {
          case (personId, Some(personAgent)) => (personId, personAgent)
        }.toMap
        val props = HouseholdActor.props(beamServices, householdId, matSimHousehold, houseHoldVehicles, membersActors, homeCoord)
        val householdActor = actorSystem.actorOf(props, HouseholdActor.buildActorName(householdId, iterId))
        houseHoldVehicles.values.foreach{
          vehicle =>
            vehicle ! AssignManager(householdActor)
            beamServices.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), vehicle)
        }
        beamServices.householdRefs.put(householdId, householdActor)
    }
  }

  private def initVehicleActors(iterId: Option[String] = None): mutable.Map[Id[Vehicle], ActorRef] =
    beamServices.vehicles.map {
      case (vehicleId, matSimVehicle) => initCarVehicle(vehicleId, matSimVehicle)
    }

  def initCarVehicle(vehicleId: Id[Vehicle], matSimVehicle: Vehicle): (Id[Vehicle], ActorRef) = {
    val desc = matSimVehicle.getType.getDescription
    val information = Option(matSimVehicle.getType.getEngineInformation)
    val powerTrain = Powertrain.PowertrainFromMilesPerGallon(information.map(_.getGasConsumption).getOrElse(Powertrain.AverageMilesPerGallon))
    val props = if (desc != null && desc.toUpperCase().contains("CAR")) {
      CarVehicle.props(beamServices, vehicleId, matSimVehicle, powerTrain)
    } else {
      //only car is supported
      CarVehicle.props(beamServices, vehicleId, matSimVehicle, powerTrain)
    }
    val beamVehicleRef = actorSystem.actorOf(props, BeamVehicle.buildActorName(matSimVehicle))


    (vehicleId, beamVehicleRef)

  }


  def subscribe(eventType: String): Unit = {
    beamServices.agentSimEventsBus.subscribe(eventSubscriber, eventType)
  }

}



