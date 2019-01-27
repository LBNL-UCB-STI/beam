package beam.agentsim.agents.household

import java.util.concurrent.TimeUnit

import akka.actor.Status.Success
import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, Terminated}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import beam.agentsim.Resource.NotifyVehicleIdle
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.PersonAgent.bodyVehicleIdFromPersonID
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{ActualVehicle, VehicleOrToken}
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.GeneralizedVot
import beam.agentsim.agents.modalbehaviors.{ChoosesMode, ModeChoiceCalculator}
import beam.agentsim.agents.planning.BeamPlan
import beam.agentsim.agents.ridehail.RideHailAgent.ModifyPassengerSchedule
import beam.agentsim.agents.ridehail.RideHailManager.RoutingResponses
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule, VehiclePersonId}
import beam.agentsim.agents.{HasTickAndTrigger, InitializeTrigger, PersonAgent}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ParkingManager.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.infrastructure.ParkingStall.NoNeed
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamRouter.RoutingResponse
import beam.router.osm.TollCalculator
import beam.sim.BeamServices
import beam.sim.population.AttributesOfIndividual
import beam.utils.RandomUtils
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.population.PopulationUtils
import org.matsim.households
import org.matsim.households.Household

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}

object HouseholdActor {

  def buildActorName(id: Id[households.Household], iterationName: Option[String] = None): String = {
    s"household-${id.toString}" + iterationName
      .map(i => s"_iter-$i")
      .getOrElse("")
  }

  def props(
    beamServices: BeamServices,
    modeChoiceCalculator: AttributesOfIndividual => ModeChoiceCalculator,
    schedulerRef: ActorRef,
    transportNetwork: TransportNetwork,
    tollCalculator: TollCalculator,
    router: ActorRef,
    rideHailManager: ActorRef,
    parkingManager: ActorRef,
    eventsManager: EventsManager,
    population: org.matsim.api.core.v01.population.Population,
    matSimHousehold: Household,
    houseHoldVehicles: Map[Id[BeamVehicle], BeamVehicle],
    homeCoord: Coord,
    sharedVehicleFleets: Seq[ActorRef] = Vector()
  ): Props = {
    Props(
      new HouseholdActor(
        beamServices,
        modeChoiceCalculator,
        schedulerRef,
        transportNetwork,
        tollCalculator,
        router,
        rideHailManager,
        parkingManager,
        eventsManager,
        population,
        matSimHousehold,
        houseHoldVehicles,
        homeCoord,
        sharedVehicleFleets
      )
    )
  }

  case class MobilityStatusInquiry(personId: Id[Person], whereWhen: SpaceTime)
  case class ReleaseVehicle(vehicle: BeamVehicle)
  case class ReleaseVehicleAndReply(vehicle: BeamVehicle)

  case class MobilityStatusResponse(streetVehicle: Vector[VehicleOrToken])

  /**
    * Implementation of intra-household interaction in BEAM using actors.
    *
    * Households group together individual agents to support vehicle allocation, escort (ride-sharing), and
    * joint travel decision-making.
    *
    * The [[HouseholdActor]] is the central arbiter for vehicle allocation during individual and joint mode choice events.
    * Any agent in a mode choice situation must send a [[MobilityStatusInquiry]] to the [[HouseholdActor]]. The
    *
    * @author dserdiuk/sfeygin
    * @param vehicles the [[BeamVehicle]]s managed by this [[Household]].
    * @see [[ChoosesMode]]
    */
  class HouseholdActor(
    beamServices: BeamServices,
    modeChoiceCalculatorFactory: AttributesOfIndividual => ModeChoiceCalculator,
    schedulerRef: ActorRef,
    transportNetwork: TransportNetwork,
    tollCalculator: TollCalculator,
    router: ActorRef,
    rideHailManager: ActorRef,
    parkingManager: ActorRef,
    eventsManager: EventsManager,
    val population: org.matsim.api.core.v01.population.Population,
    val household: Household,
    vehicles: Map[Id[BeamVehicle], BeamVehicle],
    homeCoord: Coord,
    sharedVehicleFleets: Seq[ActorRef] = Vector()
  ) extends Actor
      with HasTickAndTrigger
      with ActorLogging {

    private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
    private implicit val executionContext: ExecutionContext = context.dispatcher
    override val supervisorStrategy: OneForOneStrategy =
      OneForOneStrategy(maxNrOfRetries = 0) {
        case _: Exception      => Stop
        case _: AssertionError => Stop
      }

    schedulerRef ! ScheduleTrigger(InitializeTrigger(0), self)

    if (beamServices.beamConfig.beam.experimental.optimizer.enabled) {
      //Create the solver actor
      val solver = context.actorOf(beam.agentsim.agents.household.HouseholdSolverActor.props)
      //Tell it to begin solving
      solver ! beam.agentsim.agents.household.BeginSolving
      //Watch for it to die so that it allows this parent to complete
      context.watch(solver)
    }

    import beam.agentsim.agents.memberships.Memberships.RankedGroup._

    implicit val pop: org.matsim.api.core.v01.population.Population = population

    private var availableVehicles: List[BeamVehicle] = Nil
    private var cavPlans: List[CAVSchedule] = List()
    private var cavPassengerSchedules: Map[BeamVehicle,List[PassengerSchedule]] = Map()
    private var personToCav: Map[Id[Person],BeamVehicle] = Map()
    private var memberVehiclePersonIds: Map[Id[Person],VehiclePersonId] = Map()

    override def receive: Receive = {

      case TriggerWithId(InitializeTrigger(tick), triggerId) =>
        // If any of my vehicles are CAVs then go through scheduling process
        val cavs = vehicles.filter(_._2.beamVehicleType.automationLevel>3).map(_._2).toList
        if(cavs.size>0) {
          cavs.foreach { cav =>
            val cavDriverRef: ActorRef = context.actorOf(
              HouseholdCAVDriverAgent.props(
                HouseholdCAVDriverAgent.idFromVehicleId(cav.id),
                schedulerRef,
                beamServices,
                eventsManager,
                parkingManager,
                cav,
                Seq(),
                transportNetwork,
                tollCalculator
              )
            )
            context.watch(cavDriverRef)
            schedulerRef ! ScheduleTrigger(InitializeTrigger(0), cavDriverRef)
            cav.driver = Some(cavDriverRef)
            cav.manager = Some(self)
          }
          val householdBeamPlans = household.members.map(person => BeamPlan(person.getSelectedPlan)).toList
          val householdMatsimPlans = household.members.map(person => (person.getId -> person.getSelectedPlan)).toMap
          val cavScheduler = new HouseholdCAVScheduling(householdBeamPlans, cavs, 15 * 60, HouseholdCAVScheduling.computeSkim(householdBeamPlans))
          //          val optimalPlan = cavScheduler().sortWith(_.cost < _.cost).head.cavFleetSchedule
          val optimalPlan = cavScheduler().sortWith(_.cavFleetSchedule.map(_.schedule.size).sum > _.cavFleetSchedule.map(_.schedule.size).sum).head.cavFleetSchedule
          val requestsAndUpdatedPlans = optimalPlan.map {
            _.toRoutingRequests(beamServices)
          }
          val routingRequests = requestsAndUpdatedPlans.map {
            _._1.flatten
          }.flatten
          cavPlans = requestsAndUpdatedPlans.map(_._2)
          val memberMap = household.members.map(person => (person.getId -> person)).toMap
          optimalPlan.foreach { plan =>
            val i = 0
            personToCav = personToCav + (plan.schedule.find(_.tag == Pickup).get.person.get -> plan.cav)
            plan.schedule.foreach { cavPlan =>
              if(cavPlan.tag == Pickup){
                val oldPlan = householdMatsimPlans(cavPlan.person.get)
                val newPlan = BeamPlan.addOrReplaceLegBetweenActivities(oldPlan,PopulationUtils.createLeg("cav"),cavPlan.activity,cavPlan.nextActivity.get)
                memberMap(cavPlan.person.get).addPlan(newPlan)
                memberMap(cavPlan.person.get).setSelectedPlan(newPlan)
                memberMap(cavPlan.person.get).removePlan(oldPlan)
              }
            }
          }
          holdTickAndTriggerId(tick, triggerId)
          Future
            .sequence(routingRequests.map(akka.pattern.ask(router, _).mapTo[RoutingResponse]))
            .map(RoutingResponses(tick, _)) pipeTo self
        }
        household.members.foreach { person =>
          val attributes = person.getCustomAttributes.get("beam-attributes").asInstanceOf[AttributesOfIndividual]

          val modeChoiceCalculator = modeChoiceCalculatorFactory(attributes)

          modeChoiceCalculator.valuesOfTime += (GeneralizedVot -> attributes.valueOfTime)

          val personRef: ActorRef = context.actorOf(
            PersonAgent.props(
              schedulerRef,
              beamServices,
              modeChoiceCalculator,
              transportNetwork,
              tollCalculator,
              router,
              rideHailManager,
              parkingManager,
              eventsManager,
              person.getId,
              household,
              person.getSelectedPlan,
              sharedVehicleFleets
            ),
            person.getId.toString
          )
          context.watch(personRef)

          memberVehiclePersonIds = memberVehiclePersonIds + (person.getId -> VehiclePersonId(bodyVehicleIdFromPersonID(person.getId),person.getId,personRef))

          schedulerRef ! ScheduleTrigger(InitializeTrigger(0), personRef)
        }
        if(cavs.isEmpty)completeInitialization(triggerId)

      case RoutingResponses(tick, routingResponses) =>
        // Index the responsed by Id
        val indexedResponses = routingResponses.map(resp => (resp.requestId -> resp)).toMap
        // Create a passenger schedule for each CAV in the plan, split by passenger to be picked up
        cavPassengerSchedules = cavPlans.map{ cavSchedule =>
          val theLegsWithServiceRequest = cavSchedule.schedule.map{ serviceRequest =>
            (indexedResponses(serviceRequest.routingRequestId.get).itineraries.head.legs.head.beamLeg,serviceRequest)
          }
          //TODO the split needs to happen before a positive result, not with the positive result, hmmmm
          val splitByPickup = RandomUtils.multiSpan(theLegsWithServiceRequest)(_._2.tag != Pickup)
          val passengerSchedulesByPickup = splitByPickup.map{ subLegsWithServiceRequest =>
            var passengerSchedule = PassengerSchedule().addLegs(subLegsWithServiceRequest.map(_._1))
            subLegsWithServiceRequest.filter(_._2.person.isDefined).map{ legWithPassenger =>
              passengerSchedule = passengerSchedule.addPassenger(memberVehiclePersonIds(legWithPassenger._2.person.get),Seq(legWithPassenger._1))
            }
            passengerSchedule
          }
          (cavSchedule.cav -> passengerSchedulesByPickup)
        }.toMap
        cavPassengerSchedules.foreach { cavAndSchedules =>
          val nextSchedule = cavAndSchedules._2.head
          if(nextSchedule.schedule.map(_._2.riders.size).sum == 0){
            cavAndSchedules._1.driver.get ! ModifyPassengerSchedule(nextSchedule,tick)
          }
        }
        val (_, triggerId) = releaseTickAndTriggerId()
        completeInitialization(triggerId)


      case NotifyVehicleIdle(vId, whenWhere, _, _, _) =>
        val vehId = vId.asInstanceOf[Id[BeamVehicle]]
        vehicles(vehId).spaceTime = whenWhere
        log.debug("updated vehicle {} with location {}", vehId, whenWhere)

      case ReleaseVehicle(vehicle) =>
        vehicle.unsetDriver()
        availableVehicles = vehicle :: availableVehicles
        log.debug("Vehicle {} is now available for anyone in household {}", vehicle.id, household.getId)

      case ReleaseVehicleAndReply(vehicle) =>
        vehicle.unsetDriver()
        availableVehicles = vehicle :: availableVehicles
        log.debug("Vehicle {} is now available for anyone in household {}", vehicle.id, household.getId)
        sender() ! Success

      case MobilityStatusInquiry(personId,_) =>
        personToCav.get(personId) match {
          case Some(cav) =>
            sender() ! MobilityStatusResponse(Vector(ActualVehicle(cav)))
          case None =>
            availableVehicles = availableVehicles match {
              case firstVehicle :: rest =>
                log.debug("Vehicle {} is now taken", firstVehicle.id)
                firstVehicle.becomeDriver(sender)
                sender() ! MobilityStatusResponse(Vector(ActualVehicle(firstVehicle)))
                rest
              case Nil =>
                sender() ! MobilityStatusResponse(Vector())
                Nil
            }
        }

      case Finish =>
        context.children.foreach(_ ! Finish)
        dieIfNoChildren()
        context.become {
          case Terminated(_) =>
            dieIfNoChildren()
        }

      case Terminated(_) =>
      // Do nothing
    }

    def completeInitialization(triggerId: Long): Unit = {
      // Pipe my cars through the parking manager
      // and complete initialization only when I got them all.
      Future
        .sequence(vehicles.values.map { veh =>
          veh.manager = Some(self)
          veh.spaceTime = SpaceTime(homeCoord.getX, homeCoord.getY, 0)
          parkingManager ? ParkingInquiry(
            homeCoord,
            homeCoord,
            "home",
            AttributesOfIndividual.EMPTY,
            NoNeed,
            0,
            0
          ) flatMap {
            case ParkingInquiryResponse(stall, _) =>
              veh.useParkingStall(stall)
              self ? ReleaseVehicleAndReply(veh)
          }
        })
        .map(_ => CompletionNotice(triggerId, Vector()))
        .pipeTo(schedulerRef)
    }

    def dieIfNoChildren(): Unit = {
      if (context.children.isEmpty) {
        context.stop(self)
      } else {
        log.debug("Remaining: {}", context.children)
      }
    }
  }

}
