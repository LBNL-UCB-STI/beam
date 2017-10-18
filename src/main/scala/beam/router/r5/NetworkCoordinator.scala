package beam.router.r5

import java.io.File
import java.nio.file.Files.exists
import java.nio.file.Paths

import akka.actor.{Actor, ActorLogging, Props}
import beam.agentsim.agents.vehicles.BeamVehicle.BeamVehicleIdAndRef
import beam.agentsim.agents.vehicles._
import beam.agentsim.agents.{InitializeTrigger, TransitDriverAgent}
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.{BUS, CABLE_CAR, FERRY, RAIL, SUBWAY, TRAM}
import beam.router.Modes.isOnStreetTransit
import beam.router.RoutingModel.{BeamLeg, BeamLegWithNext, BeamPath, TransitStopsInfo}
import beam.router.r5.NetworkCoordinator.{copiedNetwork, _}
import beam.router.{Modes, TrajectoryByEdgeIdsResolver}
import beam.sim.BeamServices
import beam.utils.Objects.deepCopy
import beam.utils.reflection.ReflectionUtils
import com.conveyal.r5.profile.StreetMode
import com.conveyal.r5.streets.{StreetLayer, TarjanIslandPruner}
import com.conveyal.r5.transit.{RouteInfo, TransitLayer, TransportNetwork}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.core.trafficmonitoring.TravelTimeCalculator
import org.matsim.utils.objectattributes.attributable.Attributes
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}

import scala.collection.JavaConverters._
import scala.collection.mutable

/**
  * Created by salma_000 on 8/25/2017.
  */
class NetworkCoordinator(val beamServices: BeamServices) extends Actor with ActorLogging {

  override def receive: Receive = {
    case InitializeRouter =>
      log.info("Initializing Router")
      init
      context.parent ! RouterInitialized
      sender() ! RouterInitialized
    case InitTransit =>
      initTransit()
      sender ! TransitInited
    case networkUpdateRequest: UpdateTravelTime =>
      log.info("Received UpdateTravelTime")
      updateTimes(networkUpdateRequest.travelTimeCalculator)
      replaceNetwork

    case msg => log.info(s"Unknown message[$msg] received by NetworkCoordinator Actor.")
  }

  def init: Unit = {
    loadNetwork
  }

  def loadNetwork = {
    val networkDir = beamServices.beamConfig.beam.routing.r5.directory
    val networkDirPath = Paths.get(networkDir)
    if (!exists(networkDirPath)) {
      Paths.get(networkDir).toFile.mkdir()
    }

    val unprunedNetworkFilePath = Paths.get(networkDir, UNPRUNED_GRAPH_FILE)  // The first R5 network, created w/out island pruning
    val partiallyPrunedNetworkFile: File = unprunedNetworkFilePath.toFile
    val prunedNetworkFilePath = Paths.get(networkDir, PRUNED_GRAPH_FILE)  // The final R5 network that matches the cleaned (pruned) MATSim network
    val prunedNetworkFile: File = prunedNetworkFilePath.toFile
    if (exists(prunedNetworkFilePath)) {
      log.debug(s"Initializing router by reading network from: ${prunedNetworkFilePath.toAbsolutePath}")
      transportNetwork = TransportNetwork.read(prunedNetworkFile)
    } else {  // Need to create the unpruned and pruned networks from directory
      log.debug(s"Network file [${prunedNetworkFilePath.toAbsolutePath}] not found. ")
      log.debug(s"Initializing router by creating unpruned network from: ${networkDirPath.toAbsolutePath}")
      val partiallyPrunedTransportNetwork = TransportNetwork.fromDirectory(networkDirPath.toFile, false, false) // Uses the new signature Andrew created

      // Prune the walk network. This seems to work without problems in R5.
      new TarjanIslandPruner(partiallyPrunedTransportNetwork.streetLayer, StreetLayer.MIN_SUBGRAPH_SIZE, StreetMode.WALK).run()

      partiallyPrunedTransportNetwork.write(partiallyPrunedNetworkFile)

      ////
      // Convert car network to MATSim network, prune it, compare links one-by-one, and if it was pruned by MATSim,
      // remove the car flag in R5.
      ////
      log.debug(s"Create the cleaned MATSim network from unpuned R5 network")
      val osmFilePath = beamServices.beamConfig.beam.routing.r5.osmFile
      val rmNetBuilder = new R5MnetBuilder(partiallyPrunedNetworkFile.toString, beamServices.beamConfig.beam.routing.r5.osmMapdbFile)
      rmNetBuilder.buildMNet()
      rmNetBuilder.cleanMnet()
      log.debug(s"Pruned MATSim network created and written")
      rmNetBuilder.writeMNet(beamServices.beamConfig.matsim.modules.network.inputNetworkFile)
      log.debug(s"Prune the R5 network")
      rmNetBuilder.pruneR5()
      transportNetwork = rmNetBuilder.getR5Network

      transportNetwork.write(prunedNetworkFile)
      transportNetwork = TransportNetwork.read(prunedNetworkFile) // Needed because R5 closes DB on write
    }
    //
    beamPathBuilder = new BeamPathBuilder(transportNetwork = transportNetwork, beamServices)
    val envelopeInUTM = beamServices.geo.wgs2Utm(transportNetwork.streetLayer.envelope)
    beamServices.geo.utmbbox.maxX = envelopeInUTM.getMaxX + beamServices.beamConfig.beam.spatial.boundingBoxBuffer
    beamServices.geo.utmbbox.maxY = envelopeInUTM.getMaxY + beamServices.beamConfig.beam.spatial.boundingBoxBuffer
    beamServices.geo.utmbbox.minX = envelopeInUTM.getMinX - beamServices.beamConfig.beam.spatial.boundingBoxBuffer
    beamServices.geo.utmbbox.minY = envelopeInUTM.getMinY - beamServices.beamConfig.beam.spatial.boundingBoxBuffer
  }

  def replaceNetwork = {
    if (transportNetwork != copiedNetwork)
      transportNetwork = copiedNetwork
    else {
      /** To-do: allow switching if we just say warning or we should stop system to allow here
        * Log warning to stop or error to warning
        */
      /**
        * This case is might happen as we are operating non thread safe environment it might happen that
        * transportNetwork variable set by transportNetwork actor not possible visible to if it is not a
        * critical error as worker will be continue working on obsolete state
        */
      log.warning("Router worker continue execution on obsolete state")
      log.error("Router worker continue working on obsolete state")
      log.info("Router worker continue execution on obsolete state")
    }
  }

  def updateTimes(travelTimeCalculator: TravelTimeCalculator) = {
    copiedNetwork = deepCopy(transportNetwork).asInstanceOf[TransportNetwork]
    linkMap.keys.foreach(key => {
      val edge = copiedNetwork.streetLayer.edgeStore.getCursor(key)
      val linkId = edge.getOSMID
      if (linkId > 0) {
        val avgTime = getAverageTime(Id.createLinkId(linkId), travelTimeCalculator)
        val avgTimeShort = (avgTime * 100).asInstanceOf[Short]
        edge.setSpeed(avgTimeShort)
      }
    })
  }

  def getAverageTime(linkId: Id[Link], travelTimeCalculator: TravelTimeCalculator) = {
    val limit = 86400
    val step = 60
    val totalIterations = limit / step

    val totalTime = if (linkId != null) (0 until limit by step).map(i => travelTimeCalculator.getLinkTravelTime(linkId, i.toDouble)).sum else 0.0
    val avgTime = (totalTime / totalIterations)
    avgTime
  }


  private def overrideR5EdgeSearchRadius(newRadius: Double): Unit =
    ReflectionUtils.setFinalField(classOf[StreetLayer], "LINK_RADIUS_METERS", newRadius)

  /*
 * Plan of action:
 * Each TripSchedule within each TripPattern represents a transit vehicle trip and will spawn a transitDriverAgent and a vehicle
 * The arrivals/departures within the TripSchedules are vectors of the same length as the "stops" field in the TripPattern
 * The stop IDs will be used to extract the Coordinate of the stop from the transitLayer (don't see exactly how yet)
 * Also should hold onto the route and trip IDs and use route to lookup the transit agency which ultimately should
 * be used to decide what type of vehicle to assign
 *
 */
  def initTransit(): Unit = {
    val transitCache = mutable.Map[(Int, Int), BeamPath]()
    val transitTrips = transportNetwork.transitLayer.tripPatterns.asScala.toArray
    val transitData = transitTrips.flatMap { tripPattern =>
      //      log.debug(tripPattern.toString)
      val route = transportNetwork.transitLayer.routes.get(tripPattern.routeIndex)
      val mode = Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type))
      val transitRouteTrips = tripPattern.tripSchedules.asScala
      transitRouteTrips.filter(_.getNStops > 0).map { transitTrip =>
        // First create a unique for this trip which will become the transit agent and vehicle ids
        val tripVehId = Id.create(transitTrip.tripId, classOf[Vehicle])
        val numStops = transitTrip.departures.length
        val passengerSchedule = PassengerSchedule()

        if (numStops > 1) {
          var stopStopDepartTuple = (-1, -1, 0L)
          var previousBeamLeg: Option[BeamLeg] = None
          val travelStops = transitTrip.departures.zipWithIndex.sliding(2)
          travelStops.foreach { case Array((departureTimeFrom, from), (depatureTimeTo, to)) =>
            val duration = transitTrip.arrivals(to) - departureTimeFrom
            //XXX: inconsistency between Stop.stop_id and and data in stopIdForIndex, Stop.stop_id = stopIdForIndex + 1
            //XXX: we have to use data from stopIdForIndex otherwise router want find vehicle by beamleg in beamServices.transitVehiclesByBeamLeg
            val fromStopIdx = tripPattern.stops(from)
            val toStopIdx = tripPattern.stops(to)
            val fromStopId = tripPattern.stops(from)
            val toStopId = tripPattern.stops(to)
            val stopsInfo = TransitStopsInfo(fromStopId, toStopId)
            if(tripVehId.toString.equals("SM:43|10748241:T1|15:00") && departureTimeFrom.toLong == 1500L){
              val i =0
            }
            val transitPath = if (isOnStreetTransit(mode)) {
              transitCache.get((fromStopIdx,toStopIdx)).fold{
                val bp = beamPathBuilder.routeTransitPathThroughStreets(departureTimeFrom.toLong, fromStopIdx, toStopIdx, stopsInfo, duration)
                transitCache += ((fromStopIdx,toStopIdx)->bp)
                bp}
              {x =>
                beamPathBuilder.createFromExistingWithUpdatedTimes(x,departureTimeFrom,duration)
              }
            } else {
              val edgeIds = beamPathBuilder.resolveFirstLastTransitEdges(fromStopIdx, toStopIdx)
              BeamPath(edgeIds, Option(stopsInfo), TrajectoryByEdgeIdsResolver(transportNetwork.streetLayer, departureTimeFrom.toLong, duration))
            }
            val theLeg = BeamLeg(departureTimeFrom.toLong, mode, duration, transitPath)
            passengerSchedule.addLegs(Seq(theLeg))
            beamServices.transitVehiclesByBeamLeg += (theLeg -> tripVehId)

            previousBeamLeg.foreach { prevLeg =>
              beamServices.transitLegsByStopAndDeparture += (stopStopDepartTuple -> BeamLegWithNext(prevLeg, Some(theLeg)))
            }
            previousBeamLeg = Some(theLeg)
            val previousTransitStops: TransitStopsInfo = previousBeamLeg.get.travelPath.transitStops match {
              case Some(stops) =>
                stops
              case None =>
                TransitStopsInfo(-1, -1)
            }
            stopStopDepartTuple = (previousTransitStops.fromStopId, previousTransitStops.toStopId, previousBeamLeg.get.startTime)
          }
          beamServices.transitLegsByStopAndDeparture += (stopStopDepartTuple -> BeamLegWithNext(previousBeamLeg.get, None))
        } else {
          log.warning(s"Transit trip  ${transitTrip.tripId} has only one stop ")
          val departureStart = transitTrip.departures(0)
          val fromStopIdx = tripPattern.stops(0)
          //XXX: inconsistency between Stop.stop_id and and data in stopIdForIndex, Stop.stop_id = stopIdForIndex + 1
          //XXX: we have to use data from stopIdForIndex otherwise router want find vehicle by beamleg in beamServices.transitVehiclesByBeamLeg
          val duration = 1L
          val edgeIds = beamPathBuilder.resolveFirstLastTransitEdges(fromStopIdx)
          val stopsInfo = TransitStopsInfo(fromStopIdx, fromStopIdx)
          val transitPath = BeamPath(edgeIds, Option(stopsInfo),
            new TrajectoryByEdgeIdsResolver(transportNetwork.streetLayer, departureStart.toLong, duration))
          val theLeg = BeamLeg(departureStart.toLong, mode, duration, transitPath)
          passengerSchedule.addLegs(Seq(theLeg))
          beamServices.transitVehiclesByBeamLeg += (theLeg -> tripVehId)
        }

        (tripVehId, route, passengerSchedule)
      }
    }
    val transitScheduleToCreate = transitData.filter(_._3.schedule.nonEmpty).sortBy(_._3.getStartLeg().startTime)
    transitScheduleToCreate.foreach { case (tripVehId, route, passengerSchedule) =>
      createTransitVehicle(tripVehId, route, passengerSchedule)
    }

    log.info(s"Finished Transit initialization trips, ${transitData.length}")
  }

  def createTransitVehicle(transitVehId: Id[Vehicle], route: RouteInfo, passengerSchedule: PassengerSchedule) = {

    val mode = Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type))
    val vehicleTypeId = Id.create(mode.toString.toUpperCase + "-" + route.agency_id, classOf[VehicleType])

    val vehicleType = if (transitVehicles.getVehicleTypes.containsKey(vehicleTypeId)){
      transitVehicles.getVehicleTypes.get(vehicleTypeId);
    } else {
      log.info(s"no specific vehicleType available for mode and transit agency pair '${vehicleTypeId.toString})', using default vehicleType instead")
      transitVehicles.getVehicleTypes.get(Id.create(mode.toString.toUpperCase + "-DEFAULT", classOf[VehicleType]));
    }

    mode match {
      case (BUS | SUBWAY | TRAM | CABLE_CAR | RAIL | FERRY) if vehicleType != null =>
        val matSimTransitVehicle = VehicleUtils.getFactory.createVehicle(transitVehId, vehicleType)
        matSimTransitVehicle.getType.setDescription(mode.value)
        val consumption = Option(vehicleType.getEngineInformation).map(_.getGasConsumption).getOrElse(Powertrain.AverageMilesPerGallon)
        val transitVehProps = TransitVehicle.props(beamServices, matSimTransitVehicle.getId, TransitVehicleData(), Powertrain.PowertrainFromMilesPerGallon(consumption), matSimTransitVehicle, new Attributes())
        val transitVehRef = context.actorOf(transitVehProps, BeamVehicle.buildActorName(matSimTransitVehicle))
        beamServices.vehicles += (transitVehId -> matSimTransitVehicle)
        beamServices.vehicleRefs += (transitVehId -> transitVehRef)
        beamServices.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), transitVehRef)

        val vehicleIdAndRef = BeamVehicleIdAndRef(transitVehId, transitVehRef)
        val transitDriverId = TransitDriverAgent.createAgentIdFromVehicleId(transitVehId)
        val transitDriverAgentProps = TransitDriverAgent.props(beamServices, transitDriverId, vehicleIdAndRef, passengerSchedule)
        val transitDriver = context.actorOf(transitDriverAgentProps, transitDriverId.toString)
        beamServices.agentRefs += (transitDriverId.toString -> transitDriver)
        beamServices.transitDriversByVehicle += (transitVehId -> transitDriverId)
        beamServices.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), transitDriver)

      case _ =>
        log.error(mode + " is not supported yet")
    }
  }

  private def transitVehicles = {
    beamServices.matsimServices.getScenario.getTransitVehicles
  }


}

object NetworkCoordinator {
  val PRUNED_GRAPH_FILE = "/pruned_network.dat"
  val UNPRUNED_GRAPH_FILE = "/unpruned_network.dat"

  var transportNetwork: TransportNetwork = _
  var copiedNetwork: TransportNetwork = _
  var linkMap: Map[Int, Long] = Map()
  var beamPathBuilder: BeamPathBuilder = _

  def getOsmId(edgeIndex: Int): Long = {
    linkMap.getOrElse(edgeIndex, {
      val osmLinkId = transportNetwork.streetLayer.edgeStore.getCursor(edgeIndex).getOSMID
      linkMap += edgeIndex -> osmLinkId
      osmLinkId
    })
  }

  def props(beamServices: BeamServices) = Props(classOf[NetworkCoordinator], beamServices)
}