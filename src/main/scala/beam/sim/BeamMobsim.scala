package beam.sim

import java.lang.Double
import java.util
import java.util.Collections
import java.util.Random
import java.util.concurrent.{ThreadLocalRandom, TimeUnit}
import java.util.stream.Stream

import akka.actor.Status.Success
import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef, ActorSystem, Cancellable, DeadLetter, Identify, PoisonPill, Props, Terminated}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.BeamVehicleFuelLevelUpdate
import beam.agentsim.agents.{BeamAgent, InitializeTrigger, Population, TransitDriverAgent}
import beam.agentsim.agents.rideHail.RideHailManager.{NotifyIterationEnds, RideHailAllocationManagerTimeout}
import beam.agentsim.agents.rideHail.{RideHailSurgePricingManager, RideHailAgent, RideHailManager}
import beam.agentsim.agents.vehicles.BeamVehicleType.{Car, HumanBodyVehicle, TransitVehicle}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.QuadTreeBounds
import beam.agentsim.scheduler.{BeamAgentScheduler, Trigger}
import beam.agentsim.agents.{BeamAgent, InitializeTrigger, Population}
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, StartSchedule}
import beam.router.BeamRouter.InitTransit
import beam.router.Modes
import beam.router.Modes.BeamMode.{BUS, CABLE_CAR, FERRY, RAIL, SUBWAY, TRAM}
import beam.router.Modes.isOnStreetTransit
import beam.router.RoutingModel.{BeamLeg, BeamPath, TransitStopsInfo, WindowTime}
import beam.sim.metrics.MetricsSupport
import beam.sim.monitoring.ErrorListener
import beam.utils.{DebugLib, DebugActorWithTimer, Tick}
import com.conveyal.r5.api.util.LegMode
import com.conveyal.r5.profile.{ProfileRequest, StreetMode, StreetPath}
import com.conveyal.r5.streets.{StreetRouter, VertexStore}
import com.conveyal.r5.transit.{RouteInfo, TransitLayer, TransportNetwork}
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
    startSpan("iteration-preparation", "mobsim")
//    val iterationPreparation = iterationTrace.startSpan("iteration-preparation", "mobsim", "kamon")
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

      val initTransitions = new InitTransitions(beamServices, scenario, eventsManager, transportNetwork, context)
      initTransitions.initTransit(scheduler)
      log.info(s"Initialized transitions")

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
          endSpan("agentsim-execution", "agentsim")
//          agentsimExecution.finish()
          log.info("Ending Agentsim")
          log.info("Processing Agentsim Events (Start)")
          startSpan("agentsim-events", "agentsim")
//          agentsimEvents = iterationTrace.startSpan("agentsim-events", "agentsim", "kamon")
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
          endSpan("iteration-preparation", "mobsim")
//          iterationPreparation.finish
          log.info("Preparing new Iteration (End)")
          log.info("Starting Agentsim")
          startSpan("agentsim-execution", "agentsim")
//          agentsimExecution = iterationTrace.startSpan("agentsim-execution", "agentsim", "kamon")
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
    endSpan("agentsim-events", "agentsim")
//    agentsimEvents.finish()
    logger.info("Processing Agentsim Events (End)")
  }



}


class InitTransitions(services: BeamServices, scenario: Scenario,
                      eventsManager: EventsManager, transportNetwork: TransportNetwork,
                      context: ActorContext) extends LazyLogging {

  import scala.collection.JavaConverters._

  private var numStopsNotFound = 0

  val config = services.beamConfig.beam.routing
  val transitVehicles = scenario.getTransitVehicles

  def initTransit(scheduler: ActorRef): Map[Id[Vehicle], (RouteInfo, Seq[BeamLeg])] = {
    def createTransitVehicle(transitVehId: Id[Vehicle], route: RouteInfo, legs: Seq[BeamLeg]): Unit = {

      val mode = Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type))
      val vehicleTypeId = Id.create(mode.toString.toUpperCase + "-" + route.agency_id, classOf[VehicleType])

      val vehicleType = if (transitVehicles.getVehicleTypes.containsKey(vehicleTypeId)) {
        transitVehicles.getVehicleTypes.get(vehicleTypeId)
      } else {
        logger.debug(s"no specific vehicleType available for mode and transit agency pair '${vehicleTypeId.toString})', using default vehicleType instead")
        transitVehicles.getVehicleTypes.get(Id.create(mode.toString.toUpperCase + "-DEFAULT", classOf[VehicleType]))
      }

      mode match {
        case (BUS | SUBWAY | TRAM | CABLE_CAR | RAIL | FERRY) if vehicleType != null =>
          val matSimTransitVehicle = VehicleUtils.getFactory.createVehicle(transitVehId, vehicleType)
          matSimTransitVehicle.getType.setDescription(mode.value)
          val consumption = Option(vehicleType.getEngineInformation).map(_.getGasConsumption).getOrElse(Powertrain
            .AverageMilesPerGallon)
          //        val transitVehProps = TransitVehicle.props(services, matSimTransitVehicle.getId, TransitVehicleData
          // (), Powertrain.PowertrainFromMilesPerGallon(consumption), matSimTransitVehicle, new Attributes())
          //        val transitVehRef = context.actorOf(transitVehProps, BeamVehicle.buildActorName(matSimTransitVehicle))
          val vehicle: BeamVehicle = new BeamVehicle(Powertrain.PowertrainFromMilesPerGallon(consumption),
            matSimTransitVehicle, None, TransitVehicle, None, None)
          services.vehicles += (transitVehId -> vehicle)
          val transitDriverId = TransitDriverAgent.createAgentIdFromVehicleId(transitVehId)
          val transitDriverAgentProps = TransitDriverAgent.props(scheduler, services, transportNetwork, eventsManager, transitDriverId, vehicle, legs)
          val transitDriver = context.actorOf(transitDriverAgentProps, transitDriverId.toString)
          scheduler ! ScheduleTrigger(InitializeTrigger(0.0), transitDriver)

        case _ =>
          logger.error(mode + " is not supported yet")
      }
    }

    val activeServicesToday = transportNetwork.transitLayer.getActiveServicesForDate(services.dates.localBaseDate)
    val stopToStopStreetSegmentCache = mutable.Map[(Int, Int), Option[StreetPath]]()
    val transitTrips = transportNetwork.transitLayer.tripPatterns.asScala.toStream
    val transitData = transitTrips.flatMap { tripPattern =>
      val route = transportNetwork.transitLayer.routes.get(tripPattern.routeIndex)
      val mode = Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type))
      val transitPaths = tripPattern.stops.indices.sliding(2).map { case IndexedSeq(fromStopIdx, toStopIdx) =>
        val fromStop = tripPattern.stops(fromStopIdx)
        val toStop = tripPattern.stops(toStopIdx)
        if (config.transitOnStreetNetwork && isOnStreetTransit(mode)) {
          stopToStopStreetSegmentCache.getOrElseUpdate((fromStop, toStop), routeTransitPathThroughStreets(fromStop, toStop)) match {
            case Some(streetSeg) =>
              val edges = streetSeg.getEdges.asScala
              val startEdge = transportNetwork.streetLayer.edgeStore.getCursor(edges.head)
              val endEdge = transportNetwork.streetLayer.edgeStore.getCursor(edges.last)
              (departureTime: Long, duration: Int, vehicleId: Id[Vehicle]) =>
                BeamPath(
                  edges.map(_.intValue()).toVector,
                  Option(TransitStopsInfo(fromStop, vehicleId, toStop)),
                  SpaceTime(startEdge.getGeometry.getStartPoint.getX, startEdge.getGeometry.getStartPoint.getY, departureTime),
                  SpaceTime(endEdge.getGeometry.getEndPoint.getX, endEdge.getGeometry.getEndPoint.getY, departureTime + streetSeg.getDuration),
                  streetSeg.getDistance.toDouble / 1000)
            case None =>
              val edgeIds = resolveFirstLastTransitEdges(fromStop, toStop)
              val startEdge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIds.head)
              val endEdge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIds.last)
              (departureTime: Long, duration: Int, vehicleId: Id[Vehicle]) =>
                BeamPath(
                  edgeIds,
                  Option(TransitStopsInfo(fromStop, vehicleId, toStop)),
                  SpaceTime(startEdge.getGeometry.getStartPoint.getX, startEdge.getGeometry.getStartPoint.getY, departureTime),
                  SpaceTime(endEdge.getGeometry.getEndPoint.getX, endEdge.getGeometry.getEndPoint.getY, departureTime + duration),
                  services.geo.distLatLon2Meters(new Coord(startEdge.getGeometry.getStartPoint.getX, startEdge.getGeometry.getStartPoint.getY),
                    new Coord(endEdge.getGeometry.getEndPoint.getX, endEdge.getGeometry.getEndPoint.getY))
                )
          }
        } else {
          val edgeIds = resolveFirstLastTransitEdges(fromStop, toStop)
          val startEdge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIds.head)
          val endEdge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIds.last)
          (departureTime: Long, duration: Int, vehicleId: Id[Vehicle]) =>
            BeamPath(
              edgeIds,
              Option(TransitStopsInfo(fromStop, vehicleId, toStop)),
              SpaceTime(startEdge.getGeometry.getStartPoint.getX, startEdge.getGeometry.getStartPoint.getY, departureTime),
              SpaceTime(endEdge.getGeometry.getEndPoint.getX, endEdge.getGeometry.getEndPoint.getY, departureTime + duration),
              services.geo.distLatLon2Meters(new Coord(startEdge.getGeometry.getStartPoint.getX, startEdge.getGeometry.getStartPoint.getY),
                new Coord(endEdge.getGeometry.getEndPoint.getX, endEdge.getGeometry.getEndPoint.getY))
            )
        }
      }.toSeq
      tripPattern.tripSchedules.asScala
        .filter(tripSchedule => activeServicesToday.get(tripSchedule.serviceCode))
        .map { tripSchedule =>
          // First create a unique for this trip which will become the transit agent and vehicle ids
          val tripVehId = Id.create(tripSchedule.tripId, classOf[Vehicle])
          var legs: Seq[BeamLeg] = Nil
          tripSchedule.departures.zipWithIndex.sliding(2).foreach { case Array((departureTimeFrom, from), (departureTimeTo, to)) =>
            val duration = tripSchedule.arrivals(to) - departureTimeFrom
            legs :+= BeamLeg(departureTimeFrom.toLong, mode, duration, transitPaths(from)(departureTimeFrom.toLong, duration, tripVehId))
          }
          (tripVehId, (route, legs))
        }
    }
    val transitScheduleToCreate = transitData.toMap
    transitScheduleToCreate.foreach { case (tripVehId, (route, legs)) =>
      createTransitVehicle(tripVehId, route, legs)
    }
    logger.info(s"Finished Transit initialization trips, ${transitData.length}")
    transitScheduleToCreate
  }

  /**
    * Does point2point routing request to resolve appropriated route between stops
    *
    * @param fromStopIdx from stop
    * @param toStopIdx   to stop
    * @return
    */
  private def routeTransitPathThroughStreets(fromStopIdx: Int, toStopIdx: Int) = {

    val profileRequest = new ProfileRequest()
    //Set timezone to timezone of transport network
    profileRequest.zoneId = transportNetwork.getTimeZone

    val fromVertex = transportNetwork.streetLayer.vertexStore.getCursor(transportNetwork.transitLayer.streetVertexForStop.get(fromStopIdx))
    val toVertex = transportNetwork.streetLayer.vertexStore.getCursor(transportNetwork.transitLayer.streetVertexForStop.get(toStopIdx))
    val fromPosTransformed = services.geo.snapToR5Edge(transportNetwork.streetLayer, new Coord(fromVertex.getLon, fromVertex.getLat), 100E3, StreetMode.WALK)
    val toPosTransformed = services.geo.snapToR5Edge(transportNetwork.streetLayer, new Coord(toVertex.getLon, toVertex.getLat), 100E3, StreetMode.WALK)

    profileRequest.fromLon = fromPosTransformed.getX
    profileRequest.fromLat = fromPosTransformed.getY
    profileRequest.toLon = toPosTransformed.getX
    profileRequest.toLat = toPosTransformed.getY
    val time = WindowTime(0, services.beamConfig.beam.routing.r5.departureWindow)
    profileRequest.fromTime = time.fromTime
    profileRequest.toTime = time.toTime
    profileRequest.date = services.dates.localBaseDate
    profileRequest.directModes = util.EnumSet.copyOf(Collections.singleton(LegMode.CAR))
    profileRequest.transitModes = null
    profileRequest.accessModes = profileRequest.directModes
    profileRequest.egressModes = null

    val streetRouter = new StreetRouter(transportNetwork.streetLayer)
    streetRouter.profileRequest = profileRequest
    streetRouter.streetMode = StreetMode.valueOf("CAR")
    streetRouter.timeLimitSeconds = profileRequest.streetTime * 60
    if (streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon)) {
      if (streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon)) {
        streetRouter.route()
        val lastState = streetRouter.getState(streetRouter.getDestinationSplit)
        if (lastState != null) {
          Some(new StreetPath(lastState, transportNetwork, false))
        } else {
          None
        }
      } else {
        None
      }
    } else {
      None
    }
  }

  private def resolveFirstLastTransitEdges(stopIdxs: Int*) = {
    val edgeIds: Vector[Int] = stopIdxs.map { stopIdx =>
      if (transportNetwork.transitLayer.streetVertexForStop.get(stopIdx) >= 0) {
        val stopVertex = transportNetwork.streetLayer.vertexStore.getCursor(transportNetwork.transitLayer
          .streetVertexForStop.get(stopIdx))
        val split = transportNetwork.streetLayer.findSplit(stopVertex.getLat, stopVertex.getLon, 10000, StreetMode.CAR)
        if (split != null) {
          split.edge
        } else {
          limitedWarn(stopIdx)
          createDummyEdgeFromVertex(stopVertex)
        }
      } else {
        limitedWarn(stopIdx)
        createDummyEdge
      }
    }.toVector.distinct
    edgeIds
  }

  private def limitedWarn(stopIdx: Int): Unit = {
    if (numStopsNotFound < 5) {
      logger.warn(s"Stop $stopIdx not linked to street network.")
      numStopsNotFound = numStopsNotFound + 1
    } else if (numStopsNotFound == 5) {
      logger.warn(s"Stop $stopIdx not linked to street network. Further warnings messages will be suppressed")
      numStopsNotFound = numStopsNotFound + 1
    }
  }

  private def createDummyEdge(): Int = {
    val fromVert = transportNetwork.streetLayer.vertexStore.addVertex(38, -122)
    val toVert = transportNetwork.streetLayer.vertexStore.addVertex(38.001, -122.001)
    transportNetwork.streetLayer.edgeStore.addStreetPair(fromVert, toVert, 1000, -1).getEdgeIndex
  }

  private def createDummyEdgeFromVertex(stopVertex: VertexStore#Vertex): Int = {
    val toVert = transportNetwork.streetLayer.vertexStore.addVertex(stopVertex.getLat + 0.001, stopVertex.getLon + 0.001)
    transportNetwork.streetLayer.edgeStore.addStreetPair(stopVertex.index, toVert, 1000, -1).getEdgeIndex
  }
}



