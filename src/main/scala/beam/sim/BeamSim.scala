package beam.sim

import java.util.concurrent.TimeUnit

import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.{ActorRef, ActorSystem, Identify, PoisonPill, Props}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents._
import beam.agentsim.agents.household.HouseholdActor
import beam.agentsim.agents.household.HouseholdActor.InitializeRideHailAgent
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.BeamVehicleType.{CarVehicle, HumanBodyVehicle}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.handling.BeamEventsLogger
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.physsim.jdeqsim.AgentSimToPhysSimPlanConverter
import beam.router.BeamRouter
import beam.router.BeamRouter.InitTransit
import beam.router.gtfs.FareCalculator
import beam.sim.monitoring.ErrorListener
import com.google.inject.Inject
import glokka.Registry
import glokka.Registry.Created
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
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
  var eventsManager: EventsManager = _
  var writer: BeamEventsLogger = _
  var currentIter = 0
  var agentSimToPhysSimPlanConverter: AgentSimToPhysSimPlanConverter = new AgentSimToPhysSimPlanConverter(beamServices)
  var rideHailingAgents: Seq[ActorRef] = Nil

  private implicit val timeout = Timeout(50000, TimeUnit.SECONDS)

  override def notifyStartup(event: StartupEvent): Unit = {
    eventsManager = beamServices.matsimServices.getEvents

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

    val router = actorSystem.actorOf(BeamRouter.props(beamServices, beamServices.matsimServices.getScenario
      .getTransitVehicles, fareCalculator), "router")
    beamServices.beamRouter = router
    Await.result(beamServices.beamRouter ? Identify(0), timeout.duration)

    val rideHailingManagerFuture = beamServices.registry ? Registry.Register("RideHailingManager", RideHailingManager
      .props("RideHailingManager",
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
    cleanupHouseHolder()
    agentSimToPhysSimPlanConverter.startPhysSim()
  }

  private def cleanupRideHailingAgents(): Unit = {
    rideHailingAgents.foreach(_ ! Finish)
    rideHailingAgents = Nil
  }


  private def cleanupHouseHolder(): Unit = {
    for ((_, householdAgent) <- beamServices.householdRefs) {
      logger.debug(s"Stopping ${householdAgent.path.name} ")
      householdAgent ! PoisonPill
    }
  }

  override def notifyShutdown(event: ShutdownEvent): Unit = {
    actorSystem.stop(beamServices.schedulerRef)
    actorSystem.terminate()
  }

  def resetPop(iter: Int): Unit = {

    val random = new Random(beamServices.matsimServices.getConfig.global().getRandomSeed)

    val errorListener = actorSystem.actorOf(ErrorListener.props(iter))
    actorSystem.eventStream.subscribe(errorListener, classOf[BeamAgent.TerminatedPrematurelyEvent])
    // populateBeamServices
    beamServices.persons ++= scala.collection.JavaConverters.mapAsScalaMap(
      beamServices.matsimServices.getScenario.getPopulation.getPersons)
    //    beamServices.vehicles ++= beamServices.matsimServices.getScenario.getVehicles.getVehicles.asScala.toMap
    beamServices.households ++= beamServices.matsimServices.getScenario.getHouseholds.getHouseholds.asScala.toMap
    logger.info(
      s"Loaded ${beamServices.persons.size} people in ${beamServices.households.size} households with " +
        s"${beamServices.vehicles.size} vehicles")
    var personToHouseholdId: Map[Id[Person], Id[Household]] = Map()
    beamServices.households.foreach {
      case (householdId, matSimHousehold) =>
        personToHouseholdId = personToHouseholdId ++ matSimHousehold.getMemberIds.asScala
          .map(
            personId =>
              personId ->
                householdId)
    }

    val iterId = Option(iter.toString)

    // Initialize Person Agents
    initializePersonAgents(personToHouseholdId)

    // Init households before RHA.... RHA vehicles will initially be managed by households
    initHouseholds(iterId)

    // Init ridehailing agents
    sampleRideHailAgentsFromPop(
      beamServices.beamConfig.beam.agentsim.agents.rideHailing.numDriversAsFractionOfPopulation)

    //TODO if we can't do the following with generic Ids, then we should seriously consider abandoning typed IDs
    beamServices.personRefs.foreach {
      case (id, ref) =>
        ref ! SubscribeTransitionCallBack(errorListener) // Subscribes each person to the error listener
        beamServices.agentRefs.put(id.toString, ref)
    }
  }

  private def initializePersonAgents(personToHouseholdId: Map[Id[Person], Id[Household]]) = {
    for ((personId, matsimPerson) <- beamServices.persons.take(
      beamServices.beamConfig.beam.agentsim.numAgents)) {

      val bodyVehicleIdFromPerson = HumanBodyVehicle.createId(personId)

      val personAgentRef: ActorRef = actorSystem.actorOf(
        PersonAgent.props(beamServices,
          personId,
          personToHouseholdId(personId),
          matsimPerson.getSelectedPlan,
          bodyVehicleIdFromPerson),
        PersonAgent.buildActorName(personId)
      )

      // Human body vehicle initialization
      val matsimBodyVehicle = VehicleUtils.getFactory.createVehicle(
        bodyVehicleIdFromPerson,
        HumanBodyVehicle.MatsimHumanBodyVehicleType)
      val bodyVehicle = new BeamVehicle(
        Option(personAgentRef),
        HumanBodyVehicle.powerTrainForHumanBody(),
        matsimBodyVehicle,
        None,
        HumanBodyVehicle)

      // real vehicle( car, bus, etc.)  should be populated from config in notifyStartup
      //let's put here human body vehicle too, it should be clean up on each iteration
      beamServices.vehicles += ((bodyVehicleIdFromPerson, bodyVehicle))

      beamServices.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), personAgentRef)
      beamServices.personRefs += ((personId, personAgentRef))
    }
  }


  private def sampleRideHailAgentsFromPop(fraction: Double): Unit = {
    val numRideHailAgents: Int = math
      .round(math.min(
        beamServices.beamConfig.beam.agentsim.numAgents,
        beamServices.persons.size) * beamServices.beamConfig.beam.agentsim.agents.rideHailing
        .numDriversAsFractionOfPopulation)
      .toInt
    var totalRideShareAgents: Int = 0
    for {
      (hId: Id[Household], hh: Household) <- beamServices.households
      mId: Id[Person] <- JavaConverters.asScalaBuffer(hh.getMemberIds)
    } yield {
      totalRideShareAgents += 1
      if (totalRideShareAgents < numRideHailAgents) {
        beamServices.householdRefs(hId) ! InitializeRideHailAgent(mId)
      }
    }
  } //TODO: Initialize vehicles in household


  private def initHouseholds(iterId: Option[String] = None)(implicit ev: Id[Vehicle] => Id[BeamVehicle]): Unit = {
    val householdAttrs =
      beamServices.matsimServices.getScenario.getHouseholds.getHouseholdAttributes

    beamServices.households.foreach {
      case (householdId, matSimHousehold) =>
        //TODO a good example where projection should accompany the data
        if (householdAttrs.getAttribute(householdId.toString, "homecoordx") == null) {
          logger.error(
            s"Cannot find homeCoordX for household $householdId which will be interpreted at 0.0")
        }
        if (householdAttrs.getAttribute(householdId.toString.toLowerCase(),
          "homecoordy") == null) {
          logger.error(
            s"Cannot find homeCoordY for household $householdId which will be interpreted at 0.0")
        }
        val homeCoord = new Coord(
          householdAttrs
            .getAttribute(householdId.toString, "homecoordx")
            .asInstanceOf[Double],
          householdAttrs
            .getAttribute(householdId.toString, "homecoordy")
            .asInstanceOf[Double]
        )

        val membersActors = matSimHousehold.getMemberIds.asScala
          .map { personId =>
            (personId, beamServices.personRefs.get(personId))
          }
          .collect {
            case (personId, Some(personAgent)) => (personId, personAgent)
          }
          .toMap

        val houseHoldVehicles: Map[Id[BeamVehicle], BeamVehicle] = JavaConverters
          .collectionAsScalaIterable(matSimHousehold.getVehicleIds)
          .map({ id =>
            val matsimVehicle = JavaConverters.mapAsScalaMap(
              beamServices.matsimServices.getScenario.getVehicles.getVehicles)(
              id)
            val information = Option(matsimVehicle.getType.getEngineInformation)
            val vehicleAttribute = Option(
              beamServices.matsimServices.getScenario.getVehicles.getVehicleAttributes)
            val powerTrain = Powertrain.PowertrainFromMilesPerGallon(
              information
                .map(_.getGasConsumption)
                .getOrElse(Powertrain.AverageMilesPerGallon))
            agentsim.vehicleId2BeamVehicleId(id) -> new BeamVehicle(None,
              powerTrain,
              matsimVehicle,
              vehicleAttribute,
              CarVehicle)
          }).toMap

        val props = HouseholdActor.props(beamServices,
          householdId,
          matSimHousehold,
          houseHoldVehicles,
          membersActors,
          homeCoord)

        val householdActor = actorSystem.actorOf(
          props,
          HouseholdActor.buildActorName(householdId, iterId))

        houseHoldVehicles.values.foreach { veh => veh.manager = Some(householdActor) }


        beamServices.householdRefs.put(householdId, householdActor)
    }
  }

}



