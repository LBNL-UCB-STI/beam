package beam.agentsim.routing.opentripplanner

import java.io.File
import java.time.ZonedDateTime
import java.util
import java.util.Locale

import akka.actor.{ActorRef, Props}
import beam.agentsim.routing.BeamRouter
import beam.agentsim.routing.RoutingMessages._
import beam.agentsim.routing.opentripplanner.OpenTripPlannerRouter._
import beam.agentsim.sim.AgentsimServices
import beam.agentsim.utils.GeoUtils._
import com.vividsolutions.jts.geom.Coordinate
import org.geotools.geometry.DirectPosition2D
import org.geotools.referencing.CRS
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.population.{Person, PlanElement}
import org.matsim.facilities.Facility
import org.matsim.utils.objectattributes.attributable.Attributes
import org.opengis.referencing.operation.MathTransform
import org.opentripplanner.common.model.GenericLocation
import org.opentripplanner.graph_builder.GraphBuilder
import org.opentripplanner.routing.core.{State, TraverseMode}
import org.opentripplanner.routing.edgetype._
import org.opentripplanner.routing.error.{PathNotFoundException, TrivialPathException}
import org.opentripplanner.routing.impl._
import org.opentripplanner.routing.services.GraphService
import org.opentripplanner.routing.spt.GraphPath
import org.opentripplanner.standalone.{CommandLineParameters, Router}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._
import scala.collection.immutable.Queue

/**
  */
class OpenTripPlannerRouter(agentsimServices: AgentsimServices) extends BeamRouter {

  import beam.agentsim.sim.AgentsimServices._

  val log: Logger = LoggerFactory.getLogger(getClass)
  val baseDirectory: File = new File(beamConfig.beam.sim.sharedInputs + beamConfig.beam.routing.otp.directory)
  val routerIds: List[String] = beamConfig.beam.routing.otp.routerIds
  var graphService: Option[GraphService] = None
  var router: Option[Router] = None
  var transform: Option[MathTransform] = None
  val baseTime: Long = ZonedDateTime.parse("2016-10-17T00:00:00-07:00[UTC-07:00]").toEpochSecond

  def calcRoute(fromFacility: Facility[_], toFacility: Facility[_], departureTime: Double, person: Person): java.util.LinkedList[PlanElement] = {
    var request = new org.opentripplanner.routing.core.RoutingRequest()
    request.routerId = routerIds.head
    val fromPos = new DirectPosition2D(fromFacility.getCoord.getX, fromFacility.getCoord.getY)
    val toPos = new DirectPosition2D(toFacility.getCoord.getX, toFacility.getCoord.getY)
    val fromPosTransformed = new DirectPosition2D(fromFacility.getCoord.getX, fromFacility.getCoord.getY)
    val toPosTransformed = new DirectPosition2D(toFacility.getCoord.getX, toFacility.getCoord.getY)
    if (fromFacility.getCoord.getX > 400.0 | fromFacility.getCoord.getX < -400.0) {
      transform.get.transform(fromPos, fromPosTransformed)
      transform.get.transform(toPos, toPosTransformed)
    }
    request.from = new GenericLocation(fromPosTransformed.getY, fromPosTransformed.getX)
    request.to = new GenericLocation(toPosTransformed.getY, toPosTransformed.getX)
    request.dateTime = baseTime + departureTime.toLong % (24L * 3600L)
    request.maxWalkDistance = 804.672
    request.locale = Locale.ENGLISH
    val paths: util.List[GraphPath] = new util.ArrayList[GraphPath]()
    request.clearModes()
    request.addMode(TraverseMode.WALK)
    request.addMode(TraverseMode.CAR)
    var gpFinder = new GraphPathFinder(router.get)
    try {
      paths.addAll(gpFinder.graphPathFinderEntryPoint(request))
    } catch {
      case e: NullPointerException =>
        log.error(e.getCause.toString)
      case e: PathNotFoundException =>
//        log.error("PathNotFoundException")
      case e: TrivialPathException =>
//        log.error("TrivialPathException")
    }
    request = new org.opentripplanner.routing.core.RoutingRequest()
    request.routerId = routerIds.head
    request.from = new GenericLocation(fromPosTransformed.getY, fromPosTransformed.getX)
    request.to = new GenericLocation(toPosTransformed.getY, toPosTransformed.getX)
    request.dateTime = baseTime + departureTime.toLong % (24L * 3600L)
    request.maxWalkDistance = 804.672
    request.locale = Locale.ENGLISH
    request.clearModes()
    request.addMode(TraverseMode.WALK)
    request.addMode(TraverseMode.TRANSIT)
    request.addMode(TraverseMode.BUS)
    request.addMode(TraverseMode.RAIL)
    request.addMode(TraverseMode.SUBWAY)
    request.addMode(TraverseMode.LEG_SWITCH)
    request.addMode(TraverseMode.CABLE_CAR)
    request.addMode(TraverseMode.FERRY)
    request.addMode(TraverseMode.TRAM)
    request.addMode(TraverseMode.FUNICULAR)
    request.addMode(TraverseMode.GONDOLA)
    gpFinder = new GraphPathFinder(router.get)
    try {
      paths.addAll(gpFinder.graphPathFinderEntryPoint(request))
    } catch {
      case e: NullPointerException =>
        log.error("NullPointerException encountered in OpenTripPlanner router for request: " + request.toString)
      case e: PathNotFoundException =>
//        log.error("PathNotFoundException")
      case e: TrivialPathException =>
//        log.error("TrivialPathException")
    }

    val beamTrips = for (path: GraphPath <- paths.asScala.toVector) yield {
      val statesInGraphPath = path.states.asScala.toVector
      val edgesInGraphPath = path.edges.asScala.toVector
      var runningTime = statesInGraphPath.head.getTimeSeconds - baseTime
      val timesAlongGraphPath = for(state <- statesInGraphPath)yield()
      var edgesModesTimes: Vector[EdgeModeTime] = Vector()
      var stateIndex = 0
      var prevTime = statesInGraphPath(stateIndex).getTimeSeconds - baseTime
      while(stateIndex < statesInGraphPath.length - 1){
        val state = statesInGraphPath(stateIndex)
        if(!state.isInstanceOf[PatternDwell]) {
          val theMode: String = if (state.getBackMode != null) {
            if (state.getBackMode.name().equalsIgnoreCase("leg_switch")) {
              state.getBackEdge match {
                case _: StreetTransitLink =>
                  "PRE_BOARD"
                case _: PreAlightEdge =>
                  "PRE_ALIGHT"
                case _: PreBoardEdge =>
                  "WAITING"
                case alight: TransitBoardAlight =>
                  if (alight.boarding) {
                    "BOARDING"
                  } else {
                    "ALIGHTING"
                  }
                case _ =>
                  state.getBackMode.name()
              }
            } else {
              state.getBackMode.name()
            }
          } else {
            state.getNonTransitMode.name()
          }
          if (stateIndex == 0  || edgesInGraphPath(stateIndex-1).getGeometry == null || edgesInGraphPath(stateIndex-1).getGeometry.getCoordinates.length == 0) {
            val toCoord = new Coord(state.getVertex.getX, state.getVertex.getY)
            val fromCoord = if (state.getBackEdge == null) {
              toCoord
            } else {
              new Coord(state.getBackEdge.getFromVertex.getX, state.getBackEdge.getFromVertex.getY)
            }
            edgesModesTimes = edgesModesTimes :+ EdgeModeTime(state.getVertex.getLabel, theMode, state.getTimeSeconds - baseTime, fromCoord, toCoord)
          } else {
            val coords = (for (coordinate <- edgesInGraphPath(stateIndex-1).getGeometry.getCoordinates) yield (new Coord(coordinate.x, coordinate.y))).toVector
            val coordIt = coords.iterator
            var runningTime = prevTime
            val timeIncrement = (state.getTimeSeconds - baseTime - prevTime) / coords.length
            var fromCoord = if (coords.length > 0) {
              coords.head
            } else {
              null
            }
            while (coordIt.hasNext) {
              val toCoord = coordIt.next()
              edgesModesTimes = edgesModesTimes :+ EdgeModeTime(state.getVertex.getLabel, theMode, runningTime, fromCoord, toCoord)
              fromCoord = toCoord
              runningTime = runningTime + timeIncrement
            }

          }
          prevTime = state.getTimeSeconds - baseTime
        }
        stateIndex = stateIndex + 1
      }
      edgesModesTimes = edgesModesTimes.filter(t => !(t.mode.equals("PRE_BOARD") | t.mode.equals("PRE_ALIGHT")))

      val it = edgesModesTimes.iterator.buffered
      var activeEdgeModeTime = it.next()
      var activeLinkIds = Vector[String]()
      //TODO the coords and times should only be collected if the particular logging event that requires them is enabled
      var activeCoords = Vector[Coord]()
      var activeTimes = Vector[Long]()
      var activeMode = activeEdgeModeTime.mode
      var activeStart = activeEdgeModeTime.time
      var beamLegs = Queue[BeamLeg]()

      while (it.hasNext) {
        activeEdgeModeTime = it.next()
        val dist = distLatLon2Meters(activeEdgeModeTime.fromCoord.getX, activeEdgeModeTime.fromCoord.getY,
          activeEdgeModeTime.toCoord.getX, activeEdgeModeTime.toCoord.getY)
        if (dist > beamConfig.beam.events.filterDist) {
          log.warn(s"$activeEdgeModeTime, $dist")
        } else {
          activeLinkIds = activeLinkIds :+ activeEdgeModeTime.fromVertexLabel
          activeCoords = activeCoords :+ activeEdgeModeTime.fromCoord
          activeTimes = activeTimes :+ activeEdgeModeTime.time
        }
        if (activeEdgeModeTime.mode != activeMode) {
          beamLegs = beamLegs :+ BeamLeg(activeStart, activeMode, activeEdgeModeTime.time - activeStart,
            BeamGraphPath(activeLinkIds, Some(activeCoords), Some(activeTimes)))
          activeLinkIds = Vector[String]()
          activeCoords = Vector[Coord]()
          activeTimes = Vector[Long]()
          activeMode = activeEdgeModeTime.mode
          activeStart = activeEdgeModeTime.time
        }
      }

      // CAR only
      val beamLeg = BeamLeg(activeStart, activeMode, activeEdgeModeTime.time - activeStart, BeamGraphPath(activeLinkIds, Some(activeCoords), Some(activeTimes)))
      beamLegs = if (activeMode == "CAR") {
        beamLegs :+ BeamLeg.dummyWalk(activeStart) :+ beamLeg :+ BeamLeg.dummyWalk(edgesModesTimes.last.time)
      } else {
        beamLegs :+ beamLeg
      }

      BeamTrip(beamLegs.toVector)
    }
    val planElementList = new java.util.LinkedList[PlanElement]()
    planElementList.add(BeamItinerary(beamTrips))
    planElementList
  }

  override def receive: Receive = {
    case InitializeRouter =>
      log.info("Initializing OTP Router")
      graphService = Some(makeGraphService())
      router = Some(graphService.get.getRouter(routerIds.head))
      transform = Some(CRS.findMathTransform(CRS.decode("EPSG:26910", true), CRS.decode("EPSG:4326", true), false))
      sender() ! RouterInitialized()
    case RoutingRequest(fromFacility, toFacility, departureTime, personId) =>
      //      log.info(s"OTP Router received routing request from person $personId ($sender)")
      val person: Person = agentsimServices.matsimServices.getScenario.getPopulation.getPersons.get(personId)
      val senderRef = sender()
      val plans = calcRoute(fromFacility, toFacility, departureTime, person)
      senderRef ! RoutingResponse(plans)
    case msg =>
      log.info(s"unknown message received by OTPRouter $msg")
  }

  private def makeGraphService(): GraphService = {
    log.info("Loading graph..")

    val graphService = new GraphService()
    graphService.graphSourceFactory = new InputStreamGraphSource.FileFactory(baseDirectory)

    val params = makeParams()

    buildAndPersistGraph(graphService, params)

    if (routerIds != null && routerIds.nonEmpty) {
      val graphScanner = new GraphScanner(graphService, params.graphDirectory, params.autoScan)
      graphScanner.basePath = params.graphDirectory
      graphScanner.defaultRouterId = routerIds.head
      graphScanner.autoRegister = routerIds.asJava
      graphScanner.startup()
    }

    graphService.getRouter.graph.getVertices.forEach(vertex =>
      bbox.observeCoord(vertex.getCoordinate)
    )

    log.info("Graph loaded successfully")

    graphService
  }

  private def makeParams(): CommandLineParameters = {
    val params = new CommandLineParameters
    params.basePath = baseDirectory.getAbsolutePath
    params.port = 338080
    params.securePort = 338081
    params.routerIds = routerIds.asJava
    params.infer()
    params.autoReload = false
    params.inMemory = false
    params
  }

  private def buildAndPersistGraph(graphService: GraphService, params: CommandLineParameters): Unit = {
    routerIds.foreach(routerId => {
      val graphDirectory = new File(s"${
        baseDirectory.getAbsolutePath
      }/graphs/$routerId")
      val graphBuilder = GraphBuilder.forDirectory(params, graphDirectory)
      graphBuilder.setAlwaysRebuild(false)
      if (graphBuilder != null) {
        graphBuilder.run()
        val graph = graphBuilder.getGraph
        graph.index(new DefaultStreetVertexIndexFactory)
        graphService.registerGraph("", new MemoryGraphSource("", graph))
      }
    })
  }

  def filterSegment(a: Coord, b: Coord): Boolean = distLatLon2Meters(a.getX, b.getY, a.getX, b.getY) > beamConfig.beam.events.filterDist

  def filterLatLonList(latLons: Vector[Coord], thresh: Double): Vector[Coord] = for ((a, b) <- latLons zip latLons.drop(1) if distLatLon2Meters(a.getX, a.getY, b.getX, b.getY) < thresh) yield b

}

object OpenTripPlannerRouter {
  def props(agentsimServices: AgentsimServices) = Props(classOf[OpenTripPlannerRouter], agentsimServices)


  case class RoutingResponse(els: util.LinkedList[PlanElement])

  case class BeamItinerary(itinerary: Vector[BeamTrip]) extends PlanElement {
    override def getAttributes: Attributes = new Attributes()
  }

  case class BeamTrip(legs: Vector[BeamLeg], choiceUtility: Double = 0.0)

  object BeamTrip {
    val noneTrip: BeamTrip = BeamTrip(Vector[BeamLeg]())
  }

  case class BeamLeg(startTime: Long, mode: String, travelTime: Long, graphPath: BeamGraphPath)

  object BeamLeg {
    def dummyWalk(startTime: Long): BeamLeg = new BeamLeg(startTime, "WALK", 0, BeamGraphPath.empty())
  }

  case class BeamGraphPath(linkIds: Vector[String],
                           latLons: Option[Vector[Coord]],
                           entryTimes: Option[Vector[Long]])

  object BeamGraphPath {
    def empty(): BeamGraphPath = new BeamGraphPath(Vector[String](), None, None)
  }

  case class PathSegment(from: Coord, to: Coord) {

    import beam.agentsim.utils.GeoUtils._

    val lengthInMeters: Double = distLatLon2Meters(from.getX, from.getY, to.getX, to.getY)
  }

  case class EdgeModeTime(fromVertexLabel: String, mode: String, time: Long, fromCoord: Coord, toCoord: Coord)

}