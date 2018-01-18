package beam.sim

import java.util.concurrent.TimeUnit

import akka.actor.Status.Success
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, DeadLetter, Identify, PoisonPill, Props, Terminated}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents._
import beam.agentsim.agents.household.HouseholdActor
import beam.agentsim.agents.vehicles.BeamVehicleType.{CarVehicle, HumanBodyVehicle}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles._
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
import org.matsim.households.Household
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}

import scala.collection.JavaConverters._
import scala.collection.{JavaConverters, mutable}
import scala.concurrent.Await

/**
  * AgentSim.
  *
  * Created by sfeygin on 2/8/17.
  */
class BeamMobsim @Inject()(val beamServices: BeamServices, val transportNetwork: TransportNetwork, val scenario: Scenario, val eventsManager: EventsManager, val actorSystem: ActorSystem) extends Mobsim {
  private implicit val timeout = Timeout(50000, TimeUnit.SECONDS)

  private val log = Logger.getLogger(classOf[BeamMobsim])

  var rideHailingAgents: Seq[ActorRef] = Nil
  val rideHailingHouseholds: mutable.Set[Id[Household]] = mutable.Set[Id[Household]]()

  override def run() = {
    beamServices.clearAll
    eventsManager.initProcessing()
    val iteration = actorSystem.actorOf(Props(new Actor with ActorLogging {
      var runSender: ActorRef = _
      private val errorListener = context.actorOf(ErrorListener.props())
      context.watch(errorListener)
      context.system.eventStream.subscribe(errorListener, classOf[BeamAgent.TerminatedPrematurelyEvent])
      beamServices.schedulerRef = context.actorOf(Props(classOf[BeamAgentScheduler], beamServices.beamConfig, 3600 * 30.0, 300.0), "scheduler")
      context.system.eventStream.subscribe(errorListener, classOf[DeadLetter])
      context.watch(beamServices.schedulerRef)
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
          beamServices.schedulerRef ! StartSchedule(0)
      }

      private def cleanupRideHailingAgents(): Unit = {
        rideHailingAgents.foreach(_ ! Finish)
        rideHailingAgents = Nil
      }

      private def cleanupVehicle(): Unit = {
        // FIXME XXXX (VR): Probably no longer necessarylog.info(s"Removing Humanbody vehicles")
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
        beamServices.persons ++= scala.collection.JavaConverters.mapAsScalaMap(scenario.getPopulation.getPersons)
        beamServices.households ++= scenario.getHouseholds.getHouseholds.asScala.toMap


        val population = context.actorOf(Population.props(beamServices, transportNetwork, eventsManager), "population")
        Await.result(population ? Identify(0), timeout.duration)


        // Init households before RHA.... RHA vehicles will initially be managed by households
        initHouseholds()

        beamServices.rideHailingManager = context.actorOf(RideHailingManager.props("RideHailingManager", beamServices))
        context.watch(beamServices.rideHailingManager)

        val numRideHailAgents = math.round(math.min(beamServices.beamConfig.beam.agentsim.numAgents,beamServices.persons.size) * beamServices.beamConfig.beam.agentsim.agents.rideHailing.numDriversAsFractionOfPopulation).toInt
        val rideHailingVehicleType = scenario.getVehicles.getVehicleTypes.get(Id.create("1", classOf[VehicleType]))

        for ((k, v) <- beamServices.persons.take(numRideHailAgents)) {
          val personInitialLocation: Coord = v.getSelectedPlan.getPlanElements.iterator().next().asInstanceOf[Activity].getCoord
          //      val rideInitialLocation: Coord = new Coord(personInitialLocation.getX + initialLocationJitter * 2.0 * (1 - 0.5), personInitialLocation.getY + initialLocationJitter * 2.0 * (1 - 0.5))
          val rideInitialLocation: Coord = new Coord(personInitialLocation.getX, personInitialLocation.getY)
          val rideHailingName = s"rideHailingAgent-${k}"
          val rideHailId = Id.create(rideHailingName, classOf[RideHailingAgent])
          val rideHailVehicleId = Id.createVehicleId(s"rideHailingVehicle-person=$k") // XXXX: for now identifier will just be initial location (assumed unique)
          val rideHailVehicle: Vehicle = VehicleUtils.getFactory.createVehicle(rideHailVehicleId, rideHailingVehicleType)
          val rideHailingAgentPersonId: Id[RideHailingAgent] = Id.createPersonId(rideHailingName)
          val information = Option(rideHailVehicle.getType.getEngineInformation)
          val vehicleAttribute = Option(
            scenario.getVehicles.getVehicleAttributes)
          val powerTrain = Powertrain.PowertrainFromMilesPerGallon(
            information
              .map(_.getGasConsumption)
              .getOrElse(Powertrain.AverageMilesPerGallon))
          val rideHailBeamVehicle = new BeamVehicle(powerTrain, rideHailVehicle, vehicleAttribute, CarVehicle)
          beamServices.vehicles += (rideHailVehicleId -> rideHailBeamVehicle)
          rideHailBeamVehicle.registerResource(beamServices.rideHailingManager)
          val rideHailingAgentProps = RideHailingAgent.props(beamServices, transportNetwork, eventsManager, rideHailingAgentPersonId, rideHailBeamVehicle, rideInitialLocation)
          val rideHailingAgentRef: ActorRef = context.actorOf(rideHailingAgentProps, rideHailingName)
          context.watch(rideHailingAgentRef)
          beamServices.agentRefs.put(rideHailingName, rideHailingAgentRef)
          beamServices.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), rideHailingAgentRef)
          rideHailingAgents :+= rideHailingAgentRef
        }

        log.info(s"Initialized ${beamServices.householdRefs.size} households")
        log.info(s"Initialized ${beamServices.personRefs.size} people")
        log.info(s"Initialized ${scenario.getVehicles.getVehicles.size()} personal vehicles")
        log.info(s"Initialized ${numRideHailAgents} ride hailing agents")
        population
      }

      private def initHouseholds(iterId: Option[String] = None)(implicit ev: Id[Vehicle] => Id[BeamVehicle]): Unit = {
        val householdAttrs = scenario.getHouseholds.getHouseholdAttributes

        beamServices.households.foreach {
          case (householdId, matSimHousehold) =>
            //TODO a good example where projection should accompany the data
            if (householdAttrs.getAttribute(householdId.toString, "homecoordx") == null) {
              log.error(s"Cannot find homeCoordX for household $householdId which will be interpreted at 0.0")
            }
            if (householdAttrs.getAttribute(householdId.toString.toLowerCase(), "homecoordy") == null) {
              log.error(s"Cannot find homeCoordY for household $householdId which will be interpreted at 0.0")
            }
            val homeCoord = new Coord(householdAttrs.getAttribute(householdId.toString, "homecoordx").asInstanceOf[Double],
              householdAttrs.getAttribute(householdId.toString, "homecoordy").asInstanceOf[Double]
            )

            val membersActors = matSimHousehold.getMemberIds.asScala.map { personId =>
              (personId, beamServices.personRefs.get(personId))
            }.collect {
              case (personId, Some(personAgent)) => (personId, personAgent)
            }.toMap

            val houseHoldVehicles: Map[Id[BeamVehicle], BeamVehicle] = JavaConverters
              .collectionAsScalaIterable(matSimHousehold.getVehicleIds)
              .map({ id =>
                val matsimVehicle = JavaConverters.mapAsScalaMap(
                  scenario.getVehicles.getVehicles)(
                  id)
                val information = Option(matsimVehicle.getType.getEngineInformation)
                val vehicleAttribute = Option(
                  scenario.getVehicles.getVehicleAttributes)
                val powerTrain = Powertrain.PowertrainFromMilesPerGallon(
                  information
                    .map(_.getGasConsumption)
                    .getOrElse(Powertrain.AverageMilesPerGallon))
                agentsim.vehicleId2BeamVehicleId(id) -> new BeamVehicle(
                  powerTrain,
                  matsimVehicle,
                  vehicleAttribute,
                  CarVehicle)
              }).toMap

            houseHoldVehicles.foreach(x => beamServices.vehicles.update(x._1, x._2))

            val props = HouseholdActor.props(beamServices, eventsManager, scenario.getPopulation, householdId, matSimHousehold, houseHoldVehicles, membersActors, homeCoord)

            val householdActor = context.actorOf(
              props,
              HouseholdActor.buildActorName(householdId, iterId))

            houseHoldVehicles.values.foreach { veh => veh.manager = Some(householdActor) }


            beamServices.householdRefs.put(householdId, householdActor)
        }
      }
    }))
    Await.result(iteration ? "Run!", timeout.duration)
    log.info("Agentsim finished.")
    eventsManager.finishProcessing()
    log.info("Events drained.")
  }
}




