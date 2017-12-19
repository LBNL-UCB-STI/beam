package beam.sim

import java.util.concurrent.TimeUnit

import akka.actor.Status.Success
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Identify, PoisonPill, Props, Terminated}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.Resource.AssignManager
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents._
import beam.agentsim.agents.vehicles.BeamVehicle.BeamVehicleIdAndRef
import beam.agentsim.agents.vehicles.household.HouseholdActor
import beam.agentsim.agents.vehicles.{BeamVehicle, CarVehicle, HumanBodyVehicle, Powertrain}
import beam.agentsim.scheduler.BeamAgentScheduler
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
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.Await


/**
  * BEAM
  */

class BeamMobsim @Inject()(val beamServices: BeamServices, val transportNetwork: TransportNetwork, val scenario: Scenario, val eventsManager: EventsManager, val actorSystem: ActorSystem) extends Mobsim {
  private implicit val timeout = Timeout(50000, TimeUnit.SECONDS)

  private val log = Logger.getLogger(classOf[BeamMobsim])
  private val errorListener = actorSystem.actorOf(ErrorListener.props())
  actorSystem.eventStream.subscribe(errorListener, classOf[BeamAgent.TerminatedPrematurelyEvent])

  var rideHailingAgents: Seq[ActorRef] = Nil

  override def run() = {
    eventsManager.initProcessing()
    val iteration = actorSystem.actorOf(Props(new Actor with ActorLogging {
      var runSender: ActorRef = _
      beamServices.schedulerRef = context.actorOf(Props(classOf[BeamAgentScheduler], beamServices.beamConfig, 3600 * 30.0, 300.0), "scheduler")
      context.watch(beamServices.schedulerRef)
      beamServices.rideHailingManager = context.actorOf(RideHailingManager.props("RideHailingManager", Map[Id[VehicleType], BigDecimal](), beamServices.vehicles.toMap, beamServices, Map.empty), "ridehailingmanager")
      context.watch(beamServices.rideHailingManager)
      private val population = initializePopulation()
      context.watch(population)
      Await.result(beamServices.beamRouter ? InitTransit, timeout.duration)
      log.info(s"Transit schedule has been initialized")

      def receive = {

        case CompletionNotice(_, _) =>
          log.debug("Scheduler is finished.")
          cleanupRideHailingAgents()
          cleanupVehicle()
          cleanupHouseHolder()
          population ! Finish
          context.stop(beamServices.rideHailingManager)
          context.stop(beamServices.schedulerRef)

        case Terminated(_) =>
          if (context.children.isEmpty) {
            context.stop(self)
            runSender ! Success("Ran.")
          }

        case "Run!" =>
          runSender = sender
          log.info("Running BEAM Mobsim")
          beamServices.schedulerRef ! StartSchedule(0)
      }

      private def cleanupRideHailingAgents(): Unit = {
        rideHailingAgents.foreach(_ ! Finish)
        rideHailingAgents = Nil
      }

      private def cleanupVehicle(): Unit = {
        log.info(s"Stopping  BeamVehicle actors")
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
          log.debug(s"Stopping ${householdAgent.path.name} ")
          householdAgent ! PoisonPill
        }
      }

      def initializePopulation(): ActorRef = {
        beamServices.vehicles.clear()
        beamServices.vehicleRefs.clear()
        beamServices.agentRefs.clear()

        beamServices.vehicles ++= scenario.getVehicles.getVehicles.asScala.toMap
        beamServices.vehicleRefs ++= initVehicleActors()

        // FIXME: Must wait for population because it currently initializes global variables
        val population = context.actorOf(Population.props(beamServices, transportNetwork, eventsManager), "population")
        Await.result(population ? Identify(0), timeout.duration)

        //TODO the following should be based on config params
        //    val numRideHailAgents = 0.1
        val numRideHailAgents = math.round(math.min(beamServices.beamConfig.beam.agentsim.numAgents,beamServices.persons.size) * beamServices.beamConfig.beam.agentsim.agents.rideHailing.numDriversAsFractionOfPopulation).toInt
        val initialLocationJitter = 500 // meters

        val rideHailingVehicleType = scenario.getVehicles.getVehicleTypes().get(Id.create("1",classOf[VehicleType]))

        var rideHailingVehicles: Map[Id[Vehicle], ActorRef] = Map[Id[Vehicle], ActorRef]()

        for ((k, v) <- beamServices.persons.take(numRideHailAgents)) {
          val personInitialLocation: Coord = v.getSelectedPlan.getPlanElements.iterator().next().asInstanceOf[Activity].getCoord
          val rideInitialLocation: Coord = new Coord(personInitialLocation.getX, personInitialLocation.getY)
          val rideHailingName = s"rideHailingAgent-${k}"
          val rideHailId = Id.create(rideHailingName, classOf[RideHailingAgent])
          val rideHailVehicleId = Id.createVehicleId(s"rideHailingVehicle-person=$k") // XXXX: for now identifier will just be initial location (assumed unique)
          val rideHailVehicle: Vehicle = VehicleUtils.getFactory.createVehicle(rideHailVehicleId, rideHailingVehicleType)
          val vehicleIdAndRef: (Id[Vehicle], ActorRef) = initCarVehicle(rideHailVehicleId, rideHailVehicle)
          val rideHailingAgent = RideHailingAgent.props(beamServices, transportNetwork, eventsManager, rideHailId, BeamVehicleIdAndRef(vehicleIdAndRef), rideInitialLocation)
          val rideHailingAgentRef: ActorRef = context.actorOf(rideHailingAgent, rideHailingName)
          context.watch(rideHailingAgentRef)
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

        log.info(s"Initialized ${numRideHailAgents} ride hailing agents")

        initHouseholds()

        //TODO if we can't do the following with generic Ids, then we should seriously consider abandoning typed IDs
        beamServices.personRefs.foreach { case (id, person) =>
          beamServices.agentRefs.put(id.toString, person)
        }
        population
      }

      private def initHouseholds(iterId: Option[String] = None): Unit = {
        val householdAttrs = scenario.getHouseholds.getHouseholdAttributes
        beamServices.households.foreach {
          case (householdId, matSimHousehold) =>
            //TODO a good example where projection should accompany the data
            if(householdAttrs.getAttribute(householdId.toString, "homecoordx") == null){
              log.error(s"Cannot find homeCoordX for household ${householdId} which will be intepreted at 0.0")
            }
            if(householdAttrs.getAttribute(householdId.toString.toLowerCase(), "homecoordy") == null){
              log.error(s"Cannot find homeCoordY for household ${householdId} which will be intepreted at 0.0")
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
            val props = HouseholdActor.props(beamServices, scenario.getPopulation, householdId, matSimHousehold, houseHoldVehicles, membersActors, homeCoord)
            val householdActor = context.actorOf(props, HouseholdActor.buildActorName(householdId, iterId))
            houseHoldVehicles.values.foreach{
              vehicle =>
                vehicle ! AssignManager(householdActor)
                beamServices.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), vehicle)
            }
            beamServices.householdRefs.put(householdId, householdActor)
        }
      }

      private def initVehicleActors(): mutable.Map[Id[Vehicle], ActorRef] =
        beamServices.vehicles.map {
          case (vehicleId, matSimVehicle) => initCarVehicle(vehicleId, matSimVehicle)
        }

      def initCarVehicle(vehicleId: Id[Vehicle], matSimVehicle: Vehicle): (Id[Vehicle], ActorRef) = {
        val desc = matSimVehicle.getType.getDescription
        val information = Option(matSimVehicle.getType.getEngineInformation)
        val powerTrain = Powertrain.PowertrainFromMilesPerGallon(information.map(_.getGasConsumption).getOrElse(Powertrain.AverageMilesPerGallon))
        val props = if (desc != null && desc.toUpperCase().contains("CAR")) {
          CarVehicle.props(beamServices, eventsManager, vehicleId, matSimVehicle, powerTrain)
        } else {
          //only car is supported
          CarVehicle.props(beamServices, eventsManager,vehicleId, matSimVehicle, powerTrain)
        }
        val actorRef = context.actorOf(props, BeamVehicle.buildActorName(matSimVehicle))
        context.watch(actorRef)
        (vehicleId, actorRef)
      }
    }))
    Await.result(iteration ? "Run!", timeout.duration)
    eventsManager.finishProcessing()
  }


}
