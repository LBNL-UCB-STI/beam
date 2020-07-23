package beam.agentsim.agents.household

import java.util.concurrent.TimeUnit

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, Status, Terminated}
import akka.pattern._
import akka.util.Timeout
import beam.agentsim.Resource.NotifyVehicleIdle
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents._
import beam.agentsim.agents.choice.logit.{DestinationChoiceModel, MultinomialLogit}
import beam.agentsim.agents.modalbehaviors.ChoosesMode.{CavTripLegsRequest, CavTripLegsResponse}
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.VehicleOrToken
import beam.agentsim.agents.modalbehaviors.{ChoosesMode, ModeChoiceCalculator}
import beam.agentsim.agents.planning.BeamPlan
import beam.agentsim.agents.ridehail.RideHailAgent.{
  ModifyPassengerSchedule,
  ModifyPassengerScheduleAck,
  ModifyPassengerScheduleAcks
}
import beam.agentsim.agents.ridehail.RideHailManager.RoutingResponses
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule, PersonIdWithActorRef, VehicleCategory}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.replanning.{AddSupplementaryTrips, SupplementaryTripGenerator}
import beam.router.BeamRouter.RoutingResponse
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.CAV
import beam.router.RouteHistory
import beam.router.model.{BeamLeg, EmbodiedBeamLeg}
import beam.router.osm.TollCalculator
import beam.sim.population.AttributesOfIndividual
import beam.sim.{BeamScenario, BeamServices}
import com.conveyal.r5.transit.TransportNetwork
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.population.{Activity, Leg, Person}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.Config
import org.matsim.core.population.PopulationUtils
import org.matsim.core.utils.misc.Time
import org.matsim.households
import org.matsim.households.Household

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

object HouseholdActor {

  def buildActorName(id: Id[households.Household], iterationName: Option[String] = None): String = {
    s"household-${id.toString}" + iterationName
      .map(i => s"_iter-$i")
      .getOrElse("")
  }

  def props(
    beamServices: BeamServices,
    beamScenario: BeamScenario,
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
    sharedVehicleFleets: Seq[ActorRef] = Vector(),
    routeHistory: RouteHistory,
    boundingBox: Envelope
  ): Props = {
    Props(
      new HouseholdActor(
        beamServices,
        beamScenario,
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
        sharedVehicleFleets,
        routeHistory,
        boundingBox
      )
    )
  }

  case class MobilityStatusInquiry(personId: Id[Person], whereWhen: SpaceTime, originActivity: Activity)
  case class ReleaseVehicle(vehicle: BeamVehicle)
  case class ReleaseVehicleAndReply(vehicle: BeamVehicle, tick: Option[Int] = None)
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
    beamScenario: BeamScenario,
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
    sharedVehicleFleets: Seq[ActorRef] = Vector(),
    routeHistory: RouteHistory,
    boundingBox: Envelope
  ) extends Actor
      with HasTickAndTrigger
      with ActorLogging {
    implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
    implicit val executionContext: ExecutionContext = context.dispatcher

    override val supervisorStrategy: OneForOneStrategy =
      OneForOneStrategy(maxNrOfRetries = 0) {
        case _: Exception      => Stop
        case _: AssertionError => Stop
      }

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

    private var members: Map[Id[Person], PersonIdWithActorRef] = Map()

    // Data need to execute CAV dispatch
    private val cavPlans: mutable.ListBuffer[CAVSchedule] = mutable.ListBuffer()
    private var cavPassengerSchedules: Map[BeamVehicle, PassengerSchedule] = Map()
    private var personAndActivityToCav: Map[(Id[Person], Activity), BeamVehicle] = Map()
    private var personAndActivityToLegs: Map[(Id[Person], Activity), List[BeamLeg]] = Map()

    override def receive: Receive = {

      case TriggerWithId(InitializeTrigger(tick), triggerId) =>
        val vehiclesByCategory =
          vehicles.filter(_._2.beamVehicleType.automationLevel <= 3).groupBy(_._2.beamVehicleType.vehicleCategory)
        val fleetManagers = vehiclesByCategory.map {
          case (category, vs) =>
            val fleetManager =
              context.actorOf(Props(new HouseholdFleetManager(parkingManager, vs, homeCoord)), category.toString)
            context.watch(fleetManager)
            schedulerRef ! ScheduleTrigger(InitializeTrigger(0), fleetManager)
            fleetManager
        }

        // If any of my vehicles are CAVs then go through scheduling process
        var cavs = vehicles.values.filter(_.beamVehicleType.automationLevel > 3).toList

        if (cavs.nonEmpty) {
//          log.debug("Household {} has {} CAVs and will do some planning", household.getId, cavs.size)
          cavs.foreach { cav =>
            val cavDriverRef: ActorRef = context.actorOf(
              HouseholdCAVDriverAgent.props(
                HouseholdCAVDriverAgent.idFromVehicleId(cav.id),
                schedulerRef,
                beamServices,
                beamScenario,
                eventsManager,
                parkingManager,
                cav,
                Seq(),
                transportNetwork,
                tollCalculator
              ),
              s"cavDriver-${cav.id.toString}"
            )
            context.watch(cavDriverRef)
            cav.spaceTime = SpaceTime(homeCoord, 0)
            schedulerRef ! ScheduleTrigger(InitializeTrigger(0), cavDriverRef)
            cav.setManager(Some(self))
            cav.becomeDriver(cavDriverRef)
          }
          household.members.foreach { person =>
            person.getSelectedPlan.getPlanElements.forEach {
              case a: Activity =>
              case l: Leg =>
                if (l.getMode.equalsIgnoreCase("cav")) l.setMode("")
            }
          }

          val cavScheduler = new FastHouseholdCAVScheduling(household, cavs, beamServices)
          //val optimalPlan = cavScheduler.getKBestCAVSchedules(1).headOption.getOrElse(List.empty)
          val optimalPlan = cavScheduler.getBestProductiveSchedule
          if (optimalPlan.isEmpty || !optimalPlan.exists(_.schedule.size > 1)) {
            cavs = List()
          } else {
            val requestsAndUpdatedPlans = optimalPlan.filter(_.schedule.size > 1).map {
              _.toRoutingRequests(beamServices, transportNetwork, routeHistory)
            }
            val routingRequests = requestsAndUpdatedPlans.flatMap(_._1.flatten)
            cavPlans ++= requestsAndUpdatedPlans.map(_._2)
            val memberMap = household.members.map(person => person.getId -> person).toMap
            cavPlans.foreach { plan =>
              personAndActivityToCav = personAndActivityToCav ++ plan.schedule
                .filter(_.tag == Pickup)
                .groupBy(_.person)
                .map { pers =>
                  pers._2.map(req => (pers._1.get.personId, req.activity) -> plan.cav)
                }
                .flatten

              plan.schedule.foreach { serviceRequest =>
                if (serviceRequest.tag == Pickup) {
                  val oldPlan = memberMap(serviceRequest.person.get.personId).getSelectedPlan
                  val newPlan = BeamPlan.addOrReplaceLegBetweenActivities(
                    oldPlan,
                    PopulationUtils.createLeg("cav"),
                    serviceRequest.activity,
                    serviceRequest.nextActivity.get
                  )
                  memberMap(serviceRequest.person.get.personId).addPlan(newPlan)
                  memberMap(serviceRequest.person.get.personId).setSelectedPlan(newPlan)
                  memberMap(serviceRequest.person.get.personId).removePlan(oldPlan)
                }
              }
            }
            holdTickAndTriggerId(tick, triggerId)
            //            log.debug("Household {} is done planning", household.getId)
            Future
              .sequence(
                routingRequests.map(
                  req =>
                    akka.pattern
                      .ask(router, if (req.routeReq.isDefined) { req.routeReq.get } else { req.embodyReq.get })
                      .mapTo[RoutingResponse]
                )
              )
              .map(RoutingResponses(tick, _)) pipeTo self
          }
        }
        household.members.foreach { person =>
          val attributes = person.getCustomAttributes.get("beam-attributes").asInstanceOf[AttributesOfIndividual]
          val modeChoiceCalculator = modeChoiceCalculatorFactory(attributes)
          val selectedPlan = person.getSelectedPlan
          // Set zero endTime for plans with one activity. In other case agent sim will be started
          // before all InitializeTrigger's are completed
          if (selectedPlan.getPlanElements.size() == 1) {
            selectedPlan.getPlanElements.get(0) match {
              case elem: Activity => if (Time.isUndefinedTime(elem.getEndTime)) elem.setEndTime(0.0)
              case _              =>
            }
          }

          val personRef: ActorRef = context.actorOf(
            PersonAgent.props(
              schedulerRef,
              beamServices,
              beamScenario,
              modeChoiceCalculator,
              transportNetwork,
              tollCalculator,
              router,
              rideHailManager,
              parkingManager,
              eventsManager,
              person.getId,
              self,
              selectedPlan,
              fleetManagers ++: sharedVehicleFleets,
              routeHistory,
              boundingBox
            ),
            person.getId.toString
          )
          context.watch(personRef)
          members = members + (person.getId -> PersonIdWithActorRef(person.getId, personRef))
          schedulerRef ! ScheduleTrigger(InitializeTrigger(0), personRef)
        }
        if (cavs.isEmpty) completeInitialization(triggerId, Vector())

      case RoutingResponses(tick, routingResponses) =>
        // Check if there are any broken routes, for now we cancel the whole cav plan if this happens and give a warning
        // a more robust implementation would re-plan but without the person who's mobility led to the bad route
        if (routingResponses.exists(_.itineraries.isEmpty)) {
          log.warning(
            "Failed CAV routing responses for household {} aborting use of CAVs for this house.",
            household.getId
          )
          cavPlans.clear()
          personAndActivityToLegs = Map()
          personAndActivityToCav = Map()
          val (_, triggerId) = releaseTickAndTriggerId()
          completeInitialization(triggerId, Vector())
        } else {
          // Index the responses by Id
          val indexedResponses = routingResponses.map(resp => resp.requestId -> resp).toMap
          routingResponses.foreach { resp =>
            resp.itineraries.headOption.map { itin =>
              val theLeg = itin.legs.head.beamLeg
            }
          }
          // Create a passenger schedule for each CAV in the plan
          cavPassengerSchedules = cavPlans.map { cavSchedule =>
            val theLegs = cavSchedule.schedule.flatMap { serviceRequest =>
              serviceRequest.routingRequestId
                .map { reqId =>
                  val routeResp = indexedResponses(reqId)
                  if (routeResp.itineraries.isEmpty) {
                    Seq()
                  } else {
                    routeResp.itineraries.head.beamLegs
                  }
                }
                .getOrElse(Seq())
            }
            val passengerSchedule =
              PassengerSchedule().addLegs(BeamLeg.makeVectorLegsConsistentAsOrderdStandAloneLegs(theLegs.toVector))
            val updatedLegsIterator = passengerSchedule.schedule.keys.toIterator
            var pickDropsForGrouping: Map[PersonIdWithActorRef, List[BeamLeg]] = Map()
            var passengersToAdd = Set[PersonIdWithActorRef]()
            cavSchedule.schedule.foreach { serviceRequest =>
              if (serviceRequest.person.isDefined) {
                val person = members(serviceRequest.person.get.personId)
                if (passengersToAdd.contains(person)) {
                  passengersToAdd = passengersToAdd - person
                  if (pickDropsForGrouping.contains(person)) {
                    val legs = pickDropsForGrouping(person)
                    // Don't add the passenger to the schedule.
                    // Rather, let the PersonAgent consider CAV as Transit and make a ReservationRequest
                    personAndActivityToLegs = personAndActivityToLegs + ((
                      person.personId,
                      serviceRequest.pickupRequest.get.activity
                    ) -> legs)
                    pickDropsForGrouping = pickDropsForGrouping - person
                  }
                } else {
                  passengersToAdd = passengersToAdd + person
                }
              }
              if (serviceRequest.routingRequestId.isDefined && indexedResponses(serviceRequest.routingRequestId.get).itineraries.nonEmpty) {
                if (updatedLegsIterator.hasNext) {
                  val leg = updatedLegsIterator.next
                  passengersToAdd.foreach { pass =>
                    val legsForPerson = pickDropsForGrouping.getOrElse(pass, List()) :+ leg
                    pickDropsForGrouping = pickDropsForGrouping + (pass -> legsForPerson)
                  }
                } else {
                  throw new RuntimeException(
                    s"HH CAV schedule ran out of legs for household ${household.getId} and schedule: ${cavSchedule.schedule}"
                  )
                }
              }
            }
            cavSchedule.cav -> passengerSchedule
          }.toMap
          Future
            .sequence(
              cavPassengerSchedules
                .filter(_._2.schedule.nonEmpty)
                .map { cavAndSchedule =>
                  akka.pattern
                    .ask(
                      cavAndSchedule._1.getDriver.get,
                      ModifyPassengerSchedule(cavAndSchedule._2, tick)
                    )
                    .mapTo[ModifyPassengerScheduleAck]
                }
                .toList
            )
            .map(ModifyPassengerScheduleAcks)
            .pipeTo(self)
        }

      case Status.Failure(reason) =>
        throw new RuntimeException(reason)

      case ModifyPassengerScheduleAcks(acks) =>
        val (_, triggerId) = releaseTickAndTriggerId()
        completeInitialization(triggerId, acks.flatMap(_.triggersToSchedule).toVector)

      case CavTripLegsRequest(person, originActivity) =>
        personAndActivityToLegs.get((person.personId, originActivity)) match {
          case Some(legs) =>
            val cav = personAndActivityToCav((person.personId, originActivity))
            sender() ! CavTripLegsResponse(
              Some(cav),
              legs.map(
                bLeg =>
                  EmbodiedBeamLeg(
                    beamLeg = bLeg.copy(mode = CAV),
                    beamVehicleId = cav.id,
                    beamVehicleTypeId = cav.beamVehicleType.id,
                    asDriver = false,
                    cost = 0D,
                    unbecomeDriverOnCompletion = false,
                    isPooledTrip = false
                )
              )
            )
          case _ =>
            sender() ! CavTripLegsResponse(None, List())
        }

      case NotifyVehicleIdle(vId, whenWhere, _, _, _, _) =>
        val vehId = vId.asInstanceOf[Id[BeamVehicle]]
        vehicles(vehId).spaceTime = whenWhere
        log.debug("updated vehicle {} with location {}", vehId, whenWhere)

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

    def completeInitialization(triggerId: Long, triggersToSchedule: Vector[ScheduleTrigger]): Unit = {

      val HasEnoughFuelToBeParked: Boolean = true

      // Pipe my cars through the parking manager
      // and complete initialization only when I got them all.
      Future
        .sequence(vehicles.filter(_._2.beamVehicleType.automationLevel > 3).values.map { veh =>
          veh.setManager(Some(self))
          veh.spaceTime = SpaceTime(homeCoord.getX, homeCoord.getY, 0)
          for {
            ParkingInquiryResponse(stall, _) <- parkingManager ? ParkingInquiry(homeCoord, "init")
          } {
            veh.useParkingStall(stall)
          }
          Future.successful(())
        })
        .map(_ => CompletionNotice(triggerId, triggersToSchedule))
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
