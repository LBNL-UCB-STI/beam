package beam.sim

import java.util.concurrent.TimeUnit

import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents._
import beam.agentsim.agents.household.HouseholdActor
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.BeamVehicleType._
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.events._
import beam.agentsim.events.handling.BeamEventsLogger
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.physsim.jdeqsim.AgentSimToPhysSimPlanConverter
import beam.router.BeamRouter
import beam.router.BeamRouter.{InitTransit, InitializeRouter}
import beam.router.gtfs.FareCalculator
import beam.sim.config.BeamLoggingSetup
import beam.sim.monitoring.ErrorListener
import com.google.inject.Inject
import glokka.Registry
import glokka.Registry.Created
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.{AgentWaitingForPtEvent, EventsManager, TeleportationArrivalEvent}
import org.matsim.core.controler.events.{IterationEndsEvent, IterationStartsEvent, ShutdownEvent, StartupEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, IterationStartsListener, ShutdownListener,
  StartupListener}
import org.matsim.households.Household
import org.matsim.vehicles.{Vehicle, VehicleCapacity, VehicleType, VehicleUtils}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._
import scala.collection.{JavaConverters, mutable}
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
                       ) extends StartupListener with IterationStartsListener with IterationEndsListener with
  ShutdownListener {

  private val logger: Logger = LoggerFactory.getLogger(classOf[BeamSim])
  var eventSubscriber: ActorRef = _
  var eventsManager: EventsManager = _
  var writer: BeamEventsLogger = _
  var currentIter = 0
  var agentSimToPhysSimPlanConverter: AgentSimToPhysSimPlanConverter = new AgentSimToPhysSimPlanConverter(beamServices)

  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  override def notifyStartup(event: StartupEvent): Unit = {
    actorSystem.eventStream.setLogLevel(BeamLoggingSetup.log4jLogLevelToAkka(beamServices.beamConfig.beam.outputs
      .logging.beam.logLevel))
    eventsManager = beamServices.matsimServices.getEvents
    eventSubscriber = actorSystem.actorOf(Props(classOf[EventsSubscriber], eventsManager), EventsSubscriber
      .SUBSCRIBER_NAME)

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

    beamServices.modeChoiceCalculator = ModeChoiceCalculator(beamServices.beamConfig.beam.agentsim.agents
      .modalBehaviors.modeChoiceClass, beamServices)

    val schedulerFuture = beamServices.registry ? Registry.Register("scheduler", Props(classOf[BeamAgentScheduler],
      beamServices.beamConfig, 3600 * 30.0, 300.0))
    beamServices.schedulerRef = Await.result(schedulerFuture, timeout.duration).asInstanceOf[Created].ref

    // Before we initialize router we need to scale the transit vehicle capacities
    val alreadyScaled: mutable.HashSet[VehicleCapacity] = mutable.HashSet()
    beamServices.matsimServices.getScenario.getTransitVehicles.getVehicleTypes.asScala.foreach { case (typeId,
    vehType) =>
      val theCap: VehicleCapacity = vehType.getCapacity
      if (!alreadyScaled.contains(theCap)) {
        theCap.setSeats(math.round(theCap.getSeats * beamServices.beamConfig.beam.agentsim.tuning.transitCapacity)
          .toInt)
        theCap.setStandingRoom(math.round(theCap.getStandingRoom * beamServices.beamConfig.beam.agentsim.tuning
          .transitCapacity).toInt)
        alreadyScaled.add(theCap)
      }
    }

    val fareCalculator = new FareCalculator(beamServices.beamConfig.beam.routing.r5.directory)

    val routerFuture = beamServices.registry ? Registry.Register("router", BeamRouter.props(beamServices,
      fareCalculator))
    beamServices.beamRouter = Await.result(routerFuture, timeout.duration).asInstanceOf[Created].ref
    val routerInitFuture = beamServices.beamRouter ? InitializeRouter
    Await.result(routerInitFuture, timeout.duration)

    /*
    val physSimFuture = beamServices.registry ? Registry.Register("physSim", DummyPhysSim.props(beamServices))
    beamServices.physSim = Await.result(physSimFuture, timeout.duration).asInstanceOf[Created].ref
    val physSimInitFuture = beamServices.physSim ? new InitializePhysSim()
    Await.result(physSimInitFuture, timeout.duration)
*/

    val rideHailingManagerFuture = beamServices.registry ? Registry.Register("RideHailingManager", RideHailingManager
      .props("RideHailingManager",
        Map[Id[VehicleType], BigDecimal](), beamServices.beamVehicles.map { case (id, bv) => id -> bv.matSimVehicle }
          .toMap, beamServices, Map.empty))
    beamServices.rideHailingManager = Await.result(rideHailingManagerFuture, timeout.duration).asInstanceOf[Created].ref


  }

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
    currentIter = event.getIteration
    resetPop(event.getIteration)
    //    eventsManager.initProcessing()

    // Await.result throws an Exception in case the Future fails.
    Await.result(beamServices.beamRouter ? InitTransit, timeout.duration)
    logger.info(s"Transit schedule has been initialized")
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    //XXXX (VR): Do we need this?
    //    cleanupVehicle()
    cleanupHouseHolder()
    agentSimToPhysSimPlanConverter.startPhysSim()
  }

  //XXXX (VR): Do we need this?
  //
  //  private def cleanupVehicle(): Unit = {
  //    logger.info(s"Stopping  BeamVehicle actors")
  //    for ((_, actorRef) <- beamServices.vehicleRefs) {
  //      actorSystem.stop(actorRef)
  //
  //    }
  //    for (personId <- beamServices.persons.keys) {
  //      val bodyVehicleId = HumanBodyVehicle.createId(personId)
  //      beamServices.vehicles -= bodyVehicleId
  //    }
  //  }

  private def cleanupHouseHolder(): Unit = {
    for ((_, householdActor) <- beamServices.householdRefs) {
      logger.debug(s"Stopping ${householdActor.path.name} ")
      actorSystem.stop(householdActor)
    }
  }

  override def notifyShutdown(event: ShutdownEvent): Unit = {
    actorSystem.stop(eventSubscriber)
    actorSystem.stop(beamServices.schedulerRef)
    actorSystem.terminate()
  }

  def resetPop(iter: Int): Unit = {

    val random = new Random(beamServices.matsimServices.getConfig.global().getRandomSeed)

    val errorListener = createErrorListener(iter)

    beamServices.persons ++= scala.collection.JavaConverters.mapAsScalaMap(beamServices.matsimServices.getScenario
      .getPopulation.getPersons)
    //    beamServices.vehicles ++= beamServices.matsimServices.getScenario.getVehicles.getVehicles.asScala.toMap
    beamServices.households ++= beamServices.matsimServices.getScenario.getHouseholds.getHouseholds.asScala.toMap
    logger.info(s"Loaded ${beamServices.persons.size} people in ${beamServices.households.size} households with " +
      s"${beamServices.beamVehicles.size} vehicles")
    var personToHouseholdId: Map[Id[Person], Id[Household]] = Map()
    beamServices.households.foreach {
      case (householdId, matSimHousehold) =>
        personToHouseholdId = personToHouseholdId ++ matSimHousehold.getMemberIds.asScala.map(personId => personId ->
          householdId)
    }

    val iterId = Option(iter.toString)

    //    initVehicleActors(iterId)


    for ((personId, matsimPerson) <- beamServices.persons.take(beamServices.beamConfig.beam.agentsim.numAgents)) {
      val bodyVehicleIdFromPerson = HumanBodyVehicle.createId(personId)
      val ref: ActorRef = actorSystem.actorOf(PersonAgent.props(beamServices, personId, personToHouseholdId(personId)
        , matsimPerson.getSelectedPlan, bodyVehicleIdFromPerson), PersonAgent.buildActorName(personId))

      // Human body vehicle initialization
      val matsimBodyVehicle = VehicleUtils.getFactory.createVehicle(bodyVehicleIdFromPerson,
        HumanBodyVehicle.MatsimHumanBodyVehicleType)
      val bodyVehicle = new BeamVehicle(Option(ref), HumanBodyVehicle.powerTrainForHumanBody(),
        matsimBodyVehicle, None, HumanBodyVehicle)

      // real vehicle( car, bus, etc.)  should be populated from config in notifyStartup
      //let's put here human body vehicle too, it should be clean up on each iteration
      beamServices.beamVehicles += ((bodyVehicleIdFromPerson, bodyVehicle))

      beamServices.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), ref)
      beamServices.personRefs += ((personId, ref))
    }

    // Init households before RHA.... RHA vehicles will initially be managed by households

    initHouseholds(iterId)
    //TODO the following should be based on config params
    //    val numRideHailAgents = 0.1


    val rideHailingVehicleType = beamServices.matsimServices.getScenario.getVehicles.getVehicleTypes.get(Id.create
    ("1", classOf[VehicleType]))

    var rideHailingVehicles: Map[Id[Vehicle], ActorRef] = Map[Id[Vehicle], ActorRef]()



    //TODO if we can't do the following with generic Ids, then we should seriously consider abandoning typed IDs
    beamServices.personRefs.foreach { case (id, ref) =>
      ref ! SubscribeTransitionCallBack(errorListener) // Subscribes each person to the error listener
      beamServices.agentRefs.put(id.toString, ref)
    }
  }

  private def sampleRideHailAgentsFromPop(fraction: Double): Unit = {


    val numRideHailAgents: Int = math.round(math.min(beamServices.beamConfig.beam.agentsim.numAgents, beamServices
      .persons.size) * beamServices.beamConfig.beam.agentsim.agents.rideHailing.numDriversAsFractionOfPopulation).toInt
    var totalRideShareAgents: Int = 0
    for {
      (hId: Id[Household], hh: Household) <- beamServices.households
      vehIds: Id[Vehicle] <- JavaConverters.asScalaBuffer(hh.getVehicleIds)
      mId: Id[Person] <- JavaConverters.asScalaBuffer(hh.getMemberIds)
    } yield {
      totalRideShareAgents += 1
      if (totalRideShareAgents < numRideHailAgents) {
        beamServices.householdRefs(hId) ! InitializeRideHailAgent(mId)
      }
    }
  } //TODO: Initialize vehicles in household


  //
  //      val personInitialLocation: Coord = v.getSelectedPlan.getPlanElements.iterator().next()
  // .asInstanceOf[Activity].getCoord
  //
  //      val rideInitialLocation: Coord = new Coord(personInitialLocation.getX, personInitialLocation.getY)
  //
  //      // I don't think we want new vehicles, but are we creating brand new "agents"? I think we either split,
  //      // the population or have RHA be a containing behavior class that we pass previously
  //      // created person agents into. That way, the agent is also assigned to the correct home at first.
  //      val vehicleIdAndRef: (Id[Vehicle], ActorRef) = initCarVehicle(rideHailVehicleId, rideHailVehicle)
  //      val rideHailingAgent = RideHailingAgent.props(beamServices, rideHailId, BeamVehicleIdAndRef
  // (vehicleIdAndRef), rideInitialLocation)
  //
  //      // Initially, assume Household actor "manages" vehicles.
  //      val passengerSchedule = PassengerSchedule()
  //      beamServices.rideHailingManager ! BecomeDriver(tick, id, Some(passengerSchedule))
  //      val rideHailingAgentRef: ActorRef = actorSystem.actorOf(rideHailingAgent, rideHailingAgentName)
  //
  //      // populate maps and initialize agent via scheduler
  //      beamServices.vehicles += (rideHailVehicleId -> rideHailVehicle)
  //      beamServices.vehicleRefs += vehicleIdAndRef
  //      beamServices.agentRefs.put(rideHailingAgentName, rideHailingAgentRef)
  //      vehicleIdAndRef._2 ! AssignManager(beamServices.rideHailingManager)
  //      beamServices.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), vehicleIdAndRef._2)
  //      beamServices.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), rideHailingAgentRef)
  //
  //      rideHailingVehicles += (rideHailVehicleId -> vehicleIdAndRef._2)
  //    }


  private def initHouseholds(iterId: Option[String] = None): Unit = {
    val householdAttrs = beamServices.matsimServices.getScenario.getHouseholds.getHouseholdAttributes

    beamServices.households.foreach {
      case (householdId, matSimHousehold) =>
        //TODO a good example where projection should accompany the data
        if (householdAttrs.getAttribute(householdId.toString, "homecoordx") == null) {
          logger.error(s"Cannot find homeCoordX for household $householdId which will be interpreted at 0.0")
        }
        if (householdAttrs.getAttribute(householdId.toString.toLowerCase(), "homecoordy") == null) {
          logger.error(s"Cannot find homeCoordY for household $householdId which will be interpreted at 0.0")
        }
        val homeCoord = new Coord(householdAttrs.getAttribute(householdId.toString, "homecoordx").asInstanceOf[Double],
          householdAttrs.getAttribute(householdId.toString, "homecoordy").asInstanceOf[Double])

        val membersActors = matSimHousehold.getMemberIds.asScala.map {
          personId => (personId, beamServices.personRefs.get(personId))
        }.collect {
          case (personId, Some(personAgent)) => (personId, personAgent)
        }.toMap

        var houseHoldVehicles: Map[Id[_ <: BeamVehicle], BeamVehicle] = Map.empty

        val props = HouseholdActor.props(beamServices, householdId, matSimHousehold, houseHoldVehicles,
          membersActors, homeCoord)
        val householdActor = actorSystem.actorOf(props, HouseholdActor.buildActorName(householdId, iterId))

        houseHoldVehicles ++ JavaConverters.collectionAsScalaIterable(matSimHousehold.getVehicleIds).map { id =>
          val matsimVehicle = JavaConverters.mapAsScalaMap(beamServices.matsimServices.getScenario.getVehicles
            .getVehicles)(id)
          val information = Option(matsimVehicle.getType.getEngineInformation)
          val vehicleAttribute = Option(beamServices.matsimServices.getScenario.getVehicles.getVehicleAttributes)
          val powerTrain = Powertrain.PowertrainFromMilesPerGallon(information.map(_.getGasConsumption).getOrElse
          (Powertrain.AverageMilesPerGallon))
          id -> new BeamVehicle(Option(householdActor), powerTrain, matsimVehicle, vehicleAttribute, CarVehicle)
        }


        beamServices.householdRefs.put(householdId, householdActor)
    }
  }

  def subscribe(eventType: String): Unit = {
    beamServices.agentSimEventsBus.subscribe(eventSubscriber, eventType)
  }

  private def createErrorListener(iter: Int): ActorRef = actorSystem.actorOf(ErrorListener.props(iter))


}

case class InitializeRideHailAgent(b: Id[Person])



