package beam.agentsim.agents.household

import java.util.concurrent.TimeUnit

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, Terminated}
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.VehicleOrToken
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.GeneralizedVot
import beam.agentsim.agents.modalbehaviors.{ChoosesMode, ModeChoiceCalculator}
import beam.agentsim.agents.planning.BeamPlan
import beam.agentsim.agents.ridehail.RideHailAgent.{ModifyPassengerSchedule, ModifyPassengerScheduleAck, ModifyPassengerScheduleAcks}
import beam.agentsim.agents.ridehail.RideHailManager.RoutingResponses
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule, VehiclePersonId}
import beam.agentsim.agents.{HasTickAndTrigger, InitializeTrigger, PersonAgent}
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamRouter.RoutingResponse
import beam.router.{BeamSkimmer, RouteHistory}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, CAV, DRIVE_TRANSIT, RIDE_HAIL, RIDE_HAIL_POOLED, RIDE_HAIL_TRANSIT, TRANSIT, WALK, WALK_TRANSIT}
import beam.router.model.BeamLeg
import beam.router.osm.TollCalculator
import beam.sim.BeamServices
import beam.sim.population.AttributesOfIndividual
import beam.utils.RandomUtils
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.population.{Activity, Leg, Person}
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
    sharedVehicleFleets: Seq[ActorRef] = Vector(),
    routeHistory: RouteHistory
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
        sharedVehicleFleets,
        routeHistory
      )
    )
  }

  case class MobilityStatusInquiry(personId: Id[Person], whereWhen: SpaceTime, originActivity: Activity)
  case class ReleaseVehicle(vehicle: BeamVehicle)
  case class ReleaseVehicleAndReply(vehicle: BeamVehicle, tick: Option[Int] = None)
  case class MobilityStatusResponse(streetVehicle: Vector[VehicleOrToken])
  case class ReadyForCAVPickup(personId: Id[Person], tick: Int)
  case class CAVPickupConfirmed(triggersToSchedule: Vector[ScheduleTrigger])

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
    sharedVehicleFleets: Seq[ActorRef] = Vector(),
    routeHistory: RouteHistory
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
    private var memberVehiclePersonIds: Map[Id[Person], VehiclePersonId] = Map()

    // Data need to execute CAV dispatch
    private var cavPlans: List[CAVSchedule] = List()
    private var cavPassengerSchedules: Map[BeamVehicle, PassengerSchedule] = Map()
    private var personAndActivityToCav: Map[(Id[Person], Activity), BeamVehicle] = Map()
    private var personAndActivityToLegs: Map[(Id[Person], Activity), List[BeamLeg]] = Map()

    override def receive: Receive = {

      case TriggerWithId(InitializeTrigger(tick), triggerId) =>
        // If any of my vehicles are CAVs then go through scheduling process
        var cavs = vehicles.filter(_._2.beamVehicleType.automationLevel > 3).map(_._2).toList
        if (cavs.size > 0) {
//          log.debug("Household {} has {} CAVs and will do some planning", household.getId, cavs.size)
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
              ),
              s"cavDriver-${cav.id.toString}"
            )
            context.watch(cavDriverRef)
            cav.spaceTime = SpaceTime(homeCoord, 0)
            schedulerRef ! ScheduleTrigger(InitializeTrigger(0), cavDriverRef)
            cav.manager = Some(self)
            cav.driver = Some(cavDriverRef)
          }
          household.members.foreach{ person =>
            person.getSelectedPlan.getPlanElements.forEach{
              case a: Activity =>
              case l: Leg =>
                if(l.getMode.equalsIgnoreCase("cav"))l.setMode("")
            }
          }

          val cavScheduler = new HouseholdCAVScheduling(
            beamServices.matsimServices.getScenario,
            household,
            cavs,
            Map((Pickup, 5 * 60), (Dropoff, 10 * 60)),
            skim = new BeamSkimmer(scenario = beamServices.matsimServices.getScenario)
          )
//          var optimalPlan = cavScheduler.getKBestSchedules(1).head.cavFleetSchedule
          var optimalPlan = cavScheduler.getBestScheduleWithTheLongestCAVChain
          if(optimalPlan.isEmpty || optimalPlan.head.cavFleetSchedule.head.schedule.size<=1){
            cavs = List()
          }else{
            val requestsAndUpdatedPlans = optimalPlan.head.cavFleetSchedule.filter(_.schedule.size>1).map {
              _.toRoutingRequests(beamServices,transportNetwork,routeHistory)
            }
            val routingRequests = requestsAndUpdatedPlans.map {
              _._1.flatten
            }.flatten
            cavPlans = requestsAndUpdatedPlans.map(_._2)
            val memberMap = household.members.map(person => (person.getId -> person)).toMap
            cavPlans.foreach{plan =>
              personAndActivityToCav = personAndActivityToCav ++ (plan.schedule
                .filter(_.tag == Pickup)
                .groupBy(_.person)
                .map { pers =>
                  pers._2.map(req => (pers._1.get, req.activity) -> plan.cav)
                }
                .flatten)

              plan.schedule.foreach { serviceRequest =>
                if (serviceRequest.tag == Pickup) {
                  val oldPlan = memberMap(serviceRequest.person.get).getSelectedPlan
                  val newPlan = BeamPlan.addOrReplaceLegBetweenActivities(
                    oldPlan,
                    PopulationUtils.createLeg("cav"),
                    serviceRequest.activity,
                    serviceRequest.nextActivity.get
                  )
                  memberMap(serviceRequest.person.get).addPlan(newPlan)
                  memberMap(serviceRequest.person.get).setSelectedPlan(newPlan)
                  memberMap(serviceRequest.person.get).removePlan(oldPlan)
                }
              }
            }
            holdTickAndTriggerId(tick, triggerId)
            //            log.debug("Household {} is done planning", household.getId)
            Future
              .sequence(routingRequests.map(req => akka.pattern.ask(router, if(req.routeReq.isDefined){req.routeReq.get}else{req.embodyReq.get}).mapTo[RoutingResponse]))
              .map(RoutingResponses(tick, _)) pipeTo self
            if(household.getId.toString.equals("020600-2015000596876-0")){
              val i = 0
            }
          }
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
              self,
              person.getSelectedPlan,
              fleetManagers ++: sharedVehicleFleets
            ),
            person.getId.toString
          )
          context.watch(personRef)

          memberVehiclePersonIds = memberVehiclePersonIds + (person.getId -> VehiclePersonId(
            bodyVehicleIdFromPersonID(person.getId),
            person.getId,
            personRef
          ))

          schedulerRef ! ScheduleTrigger(InitializeTrigger(0), personRef)
        }
        if (cavs.isEmpty) completeInitialization(triggerId, Vector())

      case RoutingResponses(tick, routingResponses) =>
        // Check if there are any broken routes, for now we cancel the whole cav plan if this happens and give a warning
        // a more robust implementation would re-plan but without the person who's mobility led to the bad route
        if(routingResponses.find(_.itineraries.isEmpty).size>0){
          log.warning("Failed CAV routing responses for household {} aborting use of CAVs for this house.",household.getId)
          cavPlans = List()
          personAndActivityToLegs = Map()
          personAndActivityToCav = Map()
          val (_, triggerId) = releaseTickAndTriggerId()
          completeInitialization(triggerId,Vector())
        }else {
          // Index the responses by Id
          val indexedResponses = routingResponses.map(resp => (resp.requestId -> resp)).toMap
          routingResponses.foreach { resp =>
            if (household.getId.toString.equals("020600-2015000596876-0")) {
              val i = 0
            }
            resp.itineraries.headOption.map { itin =>
              val theLeg = itin.legs.head.beamLeg
              //            routeHistory.rememberRoute(theLeg.travelPath.linkIds,theLeg.startTime)
            }
          }
          // Create a passenger schedule for each CAV in the plan
          cavPassengerSchedules = cavPlans.map { cavSchedule =>
            var theLegs = cavSchedule.schedule.map { serviceRequest =>
              serviceRequest.routingRequestId
                .map { reqId =>
                  val routeResp = indexedResponses(reqId)
                  if (routeResp.itineraries.isEmpty) {
                    Seq()
                  } else {
                    routeResp.itineraries.head.beamLegs()
                  }
                }
                .getOrElse(Seq())
            }.flatten
            var passengerSchedule = PassengerSchedule().addLegs(theLegs)
            var pickDropsForGrouping: Map[VehiclePersonId, List[BeamLeg]] = Map()
            var passengersToAdd = Set[VehiclePersonId]()
            cavSchedule.schedule.foreach { serviceRequest =>
              if (household.getId.toString.equals("020600-2015000596876-0")) {
                val i = 0
              }
              if (serviceRequest.person.isDefined) {
                val person = memberVehiclePersonIds(serviceRequest.person.get)
                if (passengersToAdd.contains(person)) {
                  passengersToAdd = passengersToAdd - person
                  if (pickDropsForGrouping.contains(person)) {
                    val legs = pickDropsForGrouping(person)
                    passengerSchedule = passengerSchedule.addPassenger(person, legs)
                    personAndActivityToLegs = personAndActivityToLegs + ((person.personId, serviceRequest.pickupRequest.get.activity) -> legs)
                    pickDropsForGrouping = pickDropsForGrouping - person
                  }
                } else {
                  passengersToAdd = passengersToAdd + person
                }
              }
              if (serviceRequest.routingRequestId.isDefined && indexedResponses(serviceRequest.routingRequestId.get).itineraries.size > 0) {
                val leg = indexedResponses(serviceRequest.routingRequestId.get).itineraries.head.beamLegs().head
                passengersToAdd.foreach { pass =>
                  val legsForPerson = pickDropsForGrouping.get(pass).getOrElse(List()) :+ leg
                  pickDropsForGrouping = pickDropsForGrouping + (pass -> legsForPerson)
                }
              }
            }
            cavSchedule.cav -> passengerSchedule.updateStartTimes(theLegs.headOption.map(_.startTime).getOrElse(0))
          }.toMap
          Future
            .sequence(
              cavPassengerSchedules.filter(_._2.schedule.size > 0).map { cavAndSchedule =>
                akka.pattern
                  .ask(
                    cavAndSchedule._1.driver.get,
                    ModifyPassengerSchedule(cavAndSchedule._2, tick)
                  )
                  .mapTo[ModifyPassengerScheduleAck]
              }.toList
            )
            .map(ModifyPassengerScheduleAcks(_))
            .pipeTo(self)
        }

      case ModifyPassengerScheduleAcks(acks) =>
        val (_, triggerId) = releaseTickAndTriggerId()
        completeInitialization(triggerId, acks.flatMap(_.triggersToSchedule).toVector)

      case CavTripLegsRequest(person, originActivity) =>
        if(household.getId.toString.equals("1")){
          val i = 0
        }
        personAndActivityToLegs.get((person.personId, originActivity)) match {
          case Some(legs) =>
            sender() ! CavTripLegsResponse(legs)
          case _ =>
            sender() ! CavTripLegsResponse(List())
        }

      case NotifyVehicleIdle(vId, whenWhere, _, _, _) =>
        val vehId = vId.asInstanceOf[Id[BeamVehicle]]
        vehicles(vehId).spaceTime = whenWhere
        log.debug("updated vehicle {} with location {}", vehId, whenWhere)

      case ReleaseVehicle(vehicle) =>
        handleReleaseVehicle(vehicle,None)

      case ReleaseVehicleAndReply(vehicle,tick) =>
        handleReleaseVehicle(vehicle,tick)
        sender() ! Success

      case MobilityStatusInquiry(personId, _, originActivity) =>
        personAndActivityToCav.get((personId, originActivity)) match {
          case Some(cav) =>
            if(household.getId.toString.equals("1")){
              val i = 0
            }
            sender() ! MobilityStatusResponse(Vector(ActualVehicle(cav)))
          case _ =>
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

    def handleReleaseVehicle(vehicle: BeamVehicle, tickOpt: Option[Int]) = {
      if (vehicle.beamVehicleType.automationLevel <= 3) {
        vehicle.unsetDriver()
        if (!availableVehicles.contains(vehicle)) {
          availableVehicles = vehicle :: availableVehicles
        }
        log.debug("Vehicle {} is now available for anyone in household {}", vehicle.id, household.getId)
      }
    }

    def completeInitialization(triggerId: Long, triggersToSchedule: Vector[ScheduleTrigger]): Unit = {
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
