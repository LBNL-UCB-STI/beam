package beam.sim

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.PersonAgent.PersonData
import beam.agentsim.agents.TaxiAgent.TaxiData
import beam.agentsim.agents._
import beam.agentsim.agents.vehicles._
import beam.agentsim.agents.vehicles.household.HouseholdActor
import beam.agentsim.events.{EventsSubscriber, JsonFriendlyEventWriterXML, PathTraversalEvent, PointProcessEvent}
import beam.agentsim.scheduler.{BeamAgentScheduler, TriggerWithId}
import beam.agentsim.scheduler.BeamAgentScheduler.{ScheduleTrigger, StartSchedule}
import beam.physsim.{DummyPhysSim, InitializePhysSim}
import beam.router.{BeamRouter, RoutingWorker}
import beam.router.BeamRouter.InitializeRouter
import beam.utils.JsonUtils
import com.google.inject.Inject
import glokka.Registry
import glokka.Registry.Created
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.{AgentWaitingForPtEvent, EventsManager, TeleportationArrivalEvent}
import org.matsim.core.controler.events.{IterationEndsEvent, IterationStartsEvent, ShutdownEvent, StartupEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, IterationStartsListener, ShutdownListener, StartupListener}
import org.matsim.core.events.EventsUtils
import org.matsim.households.Household
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.concurrent.Await
import scala.util.Random
import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

/**
  * AgentSim entrypoint.
  * Should instantiate the [[ActorSystem]], [[BeamServices]] and interact concurrently w/ the QSim.
  *
  * Created by sfeygin on 2/8/17.
  */
class BeamSim @Inject()(private val actorSystem: ActorSystem,
                        private val services: BeamServices
                        ) extends StartupListener with IterationStartsListener with IterationEndsListener with ShutdownListener {

  private val logger: Logger = LoggerFactory.getLogger(classOf[BeamSim])
  val eventsManager: EventsManager = EventsUtils.createEventsManager()
  implicit val eventSubscriber: ActorRef = actorSystem.actorOf(Props(classOf[EventsSubscriber], eventsManager), "MATSimEventsManagerService")
  var writer: JsonFriendlyEventWriterXML = _
  var currentIter = 0

  private implicit val timeout = Timeout(5000, TimeUnit.SECONDS)

  override def notifyStartup(event: StartupEvent): Unit = {
    val scenario = services.matsimServices.getScenario

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

    val schedulerFuture = services.registry ? Registry.Register("scheduler", Props(classOf[BeamAgentScheduler],3600*30.0, 300.0))
    services.schedulerRef = Await.result(schedulerFuture, timeout.duration).asInstanceOf[Created].ref

    val routerFuture = services.registry ? Registry.Register("router", BeamRouter.props(services));
    services.beamRouter = Await.result(routerFuture, timeout.duration).asInstanceOf[Created].ref
    val routerInitFuture = services.beamRouter ? InitializeRouter
    Await.result(routerInitFuture, timeout.duration)

    val physSimFuture = services.registry ? Registry.Register("physSim", DummyPhysSim.props(services))
    services.physSim = Await.result(physSimFuture, timeout.duration).asInstanceOf[Created].ref
    val physSimInitFuture = services.physSim ? new InitializePhysSim()
    Await.result(physSimInitFuture, timeout.duration)

    val taxiManagerFuture = services.registry ? Registry.Register("taxiManager", RideHailingManager.props("taxiManager",
      fares = Map[Id[VehicleType], BigDecimal](), fleet = services.vehicles,
      services))
    services.taxiManager = Await.result(taxiManagerFuture, timeout.duration).asInstanceOf[Created].ref

  }

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
    // TODO replace magic numbers
    currentIter = event.getIteration
    writer = new JsonFriendlyEventWriterXML(services.matsimServices.getControlerIO.getIterationFilename(currentIter, "events.xml.gz"))
    eventsManager.addHandler(writer)
    resetPop(event.getIteration)
    eventsManager.initProcessing()
    Await.result(services.schedulerRef ? StartSchedule(), timeout.duration)
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    cleanupWriter()
    cleanupVehicle()
    cleanupHouseHolder()
  }

  private def cleanupWriter() = {
    eventsManager.finishProcessing()
    writer.closeFile()
    eventsManager.removeHandler(writer)
    writer = null
    JsonUtils.processEventsFileVizData(services.matsimServices.getControlerIO.getIterationFilename(currentIter, "events.xml.gz"),
      services.matsimServices.getControlerIO.getOutputFilename("trips.json"))
  }

  private def cleanupVehicle() = {
    logger.info(s"Stopping  BeamVehicle actors")
    for ( (_, actorRef) <- services.vehicleRefs) {
      actorSystem.stop(actorRef)

    }
    for( personId <- services.persons.keys) {
      val bodyVehicleId = HumanBodyVehicle.createId(personId)
      services.vehicles -= bodyVehicleId
    }
  }

  private def cleanupHouseHolder() = {
    for ( (_,  householdActor) <- services.householdRefs) {
       logger.debug(s"Stopping ${householdActor.path.name} ")
       actorSystem.stop(householdActor)
    }
  }

  override def notifyShutdown(event: ShutdownEvent): Unit = {

    if (writer != null && event.isUnexpected) {
      cleanupWriter()
    }
    actorSystem.stop(eventSubscriber)
    actorSystem.stop(services.schedulerRef)
    actorSystem.terminate()
  }

  def resetPop(iter: Int): Unit = {
    services.persons = ListMap(scala.collection.JavaConverters.mapAsScalaMap(services.matsimServices.getScenario.getPopulation.getPersons).toSeq.sortBy(_._1): _*)
    services.vehicles = services.matsimServices.getScenario.getVehicles.getVehicles.asScala.toMap
    services.households = services.matsimServices.getScenario.getHouseholds.getHouseholds.asScala.toMap
    var personToHouseholdId: Map[Id[Person],Id[Household]] = Map()
    services.households.map {
      case (householdId, matSimHousehold) =>
        personToHouseholdId = personToHouseholdId ++ matSimHousehold.getMemberIds.asScala.map(personId => (personId -> householdId))
    }

    val iterId = Option(iter.toString)
    services.vehicleRefs ++= initVehicleActors(iterId)

    // Every Person gets a HumanBodyVehicle
    val matsimHumanBodyVehicleType = VehicleUtils.getFactory.createVehicleType(Id.create("HumanBodyVehicle",classOf[VehicleType]))
    matsimHumanBodyVehicleType.setDescription("Human")
    var bodyVehicles : Map[Id[Vehicle], Vehicle] = Map()
    for ((personId, matsimPerson) <- services.persons.take(services.beamConfig.beam.agentsim.numAgents)) {
      val bodyVehicleIdFromPerson = HumanBodyVehicle.createId(personId)
      val matsimBodyVehicle = VehicleUtils.getFactory.createVehicle(bodyVehicleIdFromPerson,matsimHumanBodyVehicleType)
      val bodyVehicleRef = actorSystem.actorOf(HumanBodyVehicle.props(services, matsimBodyVehicle, personId, HumanBodyVehicle.PowertrainForHumanBody()),BeamVehicle.buildActorName(matsimBodyVehicle))
      services.vehicleRefs += ((bodyVehicleIdFromPerson, bodyVehicleRef))
      // real vehicle( car, bus, etc.)  should be populated from config in notifyStartup
      //let's put here human body vehicle too, it should be clean up on each iteration
      services.vehicles += ((bodyVehicleIdFromPerson, matsimBodyVehicle))
      services.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), bodyVehicleRef)
      val ref: ActorRef = actorSystem.actorOf(PersonAgent.props(services, personId, personToHouseholdId.get(personId).get, matsimPerson.getSelectedPlan, bodyVehicleIdFromPerson), PersonAgent.buildActorName(personId))
      services.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), ref)
      services.personRefs += ((personId, ref))
    }
    // Generate taxis and intialize them to be located within ~initialLocationJitter km of a subset of agents
    //TODO re-enable the following based on config params and after TaxiAgents have been re-factored
//    val taxiFraction = 0.1
//    val initialLocationJitter = 2000 // meters
//    for((k,v) <- services.persons.take(math.round(taxiFraction * services.persons.size).toInt)){
//      val personInitialLocation: Coord = v.getSelectedPlan.getPlanElements.iterator().next().asInstanceOf[Activity].getCoord
//      val taxiInitialLocation: Coord = new Coord(personInitialLocation.getX + initialLocationJitter * 2.0 * (Random.nextDouble() - 0.5),personInitialLocation.getY + initialLocationJitter * 2.0 * (Random.nextDouble() - 0.5))
//      val props = Props(classOf[TaxiAgent], Id.create(k.toString,TaxiAgent.getClass), TaxiData(taxiInitialLocation), services)
//      val ref: ActorRef = actorSystem.actorOf(props, s"taxi_${k.toString}_$iter")
//      services.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), ref)
//    }
    services.householdRefs = initHouseholds(iterId)

    //TODO if we can't do the following with generic Ids, then we should seriously consider abandoning typed IDs
    services.personRefs.foreach{case(id, ref) =>
      services.agentRefs = services.agentRefs + (id.toString() -> ref)
    }
  }

  private def initHouseholds(iterId: Option[String] = None)  = {
    val householdAttrs = services.matsimServices.getScenario.getHouseholds.getHouseholdAttributes
    val actors = services.households.map {
      case (householdId, matSimHousehold) =>
        //TODO a good example where projection should accompany the data
        val homeCoord = new Coord(householdAttrs.getAttribute(householdId.toString,"homeCoordX").asInstanceOf[Double],
          householdAttrs.getAttribute(householdId.toString,"homeCoordY").asInstanceOf[Double])
        val houseHoldVehicles = matSimHousehold.getVehicleIds.asScala.map {
          vehicleId =>
          val vehicleActRef = services.vehicleRefs.get(vehicleId)
          (vehicleId, vehicleActRef)
        }.collect {
          case (vehicleId, Some(vehicleAgent)) =>
            (vehicleId, vehicleAgent)
        }.toMap
        val membersActors = matSimHousehold.getMemberIds.asScala.map {
          personId => (personId, services.personRefs.get(personId))
        }.collect {
          case (personId, Some(personAgent)) => (personId, personAgent)
        }.toMap
        val props = HouseholdActor.props(services, householdId, matSimHousehold, houseHoldVehicles, membersActors, homeCoord)
        val householdActor = actorSystem.actorOf(props, HouseholdActor.buildActorName(householdId, iterId))
        householdActor ! InitializeTrigger(0)
        (householdId, householdActor)
    }
    actors
  }

  private def initVehicleActors(iterId: Option[String] = None) = {
    val actors = services.vehicles.map {
      case (vehicleId, matSimVehicle) =>
        val desc = matSimVehicle.getType.getDescription
        val information = Option(matSimVehicle.getType.getEngineInformation)
        val powerTrain = Powertrain.PowertrainFromMilesPerGallon(information.map(_.getGasConsumption).getOrElse(Powertrain.AverageMilesPerGallon))
        val props = if (desc != null && desc.toUpperCase().contains("CAR")) {
            CarVehicle.props(services, vehicleId, matSimVehicle, powerTrain)
        } else {
          //only car is supported
          CarVehicle.props(services, vehicleId, matSimVehicle, powerTrain)
        }
        val beamVehicleRef = actorSystem.actorOf(props, BeamVehicle.buildActorName(matSimVehicle))
        services.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), beamVehicleRef)
        (vehicleId, beamVehicleRef)
    }
    actors
  }

  def subscribe(eventType: String): Unit = {
    services.agentSimEventsBus.subscribe(eventSubscriber, eventType)
  }
}



