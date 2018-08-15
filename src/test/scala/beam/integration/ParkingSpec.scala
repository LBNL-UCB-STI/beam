package beam.integration

import beam.agentsim.events.{
  LeavingParkingEventAttrs,
  ModeChoiceEvent,
  ParkEventAttrs,
  PathTraversalEvent
}
import beam.sim.BeamHelper
import com.typesafe.config.ConfigValueFactory
import org.matsim.api.core.v01.events.Event
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.core.events.handler.BasicEventHandler
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.collection.immutable.Queue
import scala.collection.mutable.ArrayBuffer

class ParkingSpec
    extends WordSpecLike
    with BeforeAndAfterAll
    with Matchers
    with BeamHelper
    with IntegrationSpecCommon
    with EventsFileHandlingCommon {

  def collectEvents(filePath: String): Queue[Event] = {
    var events: Queue[Event] = Queue()
    val handler = new BasicEventHandler {
      def handleEvent(event: Event): Unit = {
        events = events :+ event
      }
    }
    val eventsMan = EventsUtils.createEventsManager()
    eventsMan.addHandler(handler)

    val reader = new MatsimEventsReader(eventsMan)
    reader.readFile(filePath)

    events
  }

  def runAndCollectEvents(parkingScenario: String): Queue[Event] = {
    runAndCollectForIterations(parkingScenario, 1).head
  }

  def runAndCollectForIterations(parkingScenario: String, iterations: Int): Seq[Queue[Event]] = {
    val config = baseConfig
      .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml,csv"))
      .withValue("beam.routing.transitOnStreetNetwork", ConfigValueFactory.fromAnyRef("true"))
      .withValue(
        "beam.agentsim.taz.parking",
        ConfigValueFactory.fromAnyRef(s"test/input/beamville/taz-parking-$parkingScenario.csv")
      )
      .withValue(
        "beam.agentsim.agents.modalBehaviors.modeChoiceClass",
        ConfigValueFactory.fromAnyRef("ModeChoiceMultinomialLogit")
      )
//      .withValue("beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.car_intercept", ConfigValueFactory.fromAnyRef(50))
      .withValue(
        "beam.outputs.events.overrideWritingLevels",
        ConfigValueFactory.fromAnyRef(
          "beam.agentsim.events.ParkEvent:VERBOSE, beam.agentsim.events.LeavingParkingEvent:VERBOSE, org.matsim.api.core.v01.events.ActivityEndEvent:REGULAR, org.matsim.api.core.v01.events.ActivityStartEvent:REGULAR, org.matsim.api.core.v01.events.PersonEntersVehicleEvent:REGULAR, org.matsim.api.core.v01.events.PersonLeavesVehicleEvent:REGULAR, beam.agentsim.events.ModeChoiceEvent:VERBOSE, beam.agentsim.events.PathTraversalEvent:VERBOSE"
        )
      )
      .withValue(
        "matsim.modules.controler.lastIteration",
        ConfigValueFactory.fromAnyRef(iterations)
      )
      .resolve()

    val matsimConfig = runBeamWithConfig(config)._1
    val queueEvents = ArrayBuffer[Queue[Event]]()
    for (i <- 0 until iterations) {
      val filePath = getEventsFilePath(matsimConfig, "xml", i).getAbsolutePath
      queueEvents.append(collectEvents(filePath))
    }
    queueEvents
  }

  lazy val limitedEvents: Seq[Queue[Event]] = runAndCollectForIterations("limited", 10)
  lazy val defaultEvents: Seq[Queue[Event]] = runAndCollectForIterations("default", 10)
  lazy val expensiveEvents: Seq[Queue[Event]] = runAndCollectForIterations("expensive", 10)
  lazy val emptyEvents: Seq[Queue[Event]] = runAndCollectForIterations("empty", 10)

  lazy val filterForCarMode: Seq[Event] => Int = { events =>
    events.count { e =>
      val mMode = Option(e.getAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE))
      e.getEventType.equals(ModeChoiceEvent.EVENT_TYPE) && mMode.exists(_.equals("car"))
    }
  }

   "Parking system " must {
    "guarantee at least some parking used " in  {
      val parkingEvents =
        defaultEvents.head.filter(e => ParkEventAttrs.EVENT_TYPE.equals(e.getEventType))
      parkingEvents.size should be > 0
    }

    "departure and arrival should be from same parking 4 tuple" in {

      val parkingEvents = defaultEvents.head.filter(
        e =>
          ParkEventAttrs.EVENT_TYPE.equals(e.getEventType) || LeavingParkingEventAttrs.EVENT_TYPE
            .equals(e.getEventType)
      )

      val groupedByVehicle = parkingEvents.foldLeft(Map[String, ArrayBuffer[Event]]()) {
        case (c, ev) =>
          val vehId = ev.getAttributes.get(ParkEventAttrs.ATTRIBUTE_VEHICLE_ID)
          val array = c.getOrElse(vehId, ArrayBuffer[Event]())
          array.append(ev)
          c.updated(vehId, array)
      }

      val res = groupedByVehicle.map {
        case (id, x) =>
          val (parkEvents, leavingEvents) =
            x.partition(e => ParkEventAttrs.EVENT_TYPE.equals(e.getEventType))

          //First and last park events won't match
          val parkEventsWithoutLast = parkEvents.dropRight(1)
          val leavingParkEventsWithoutFirst = leavingEvents.tail

          parkEventsWithoutLast.size shouldEqual leavingParkEventsWithoutFirst.size
          (id, parkEventsWithoutLast zip leavingParkEventsWithoutFirst)
      }

      val isSameArrivalAndDeparture = res.forall {
        case (_, array) =>
          array.forall {
            case (evA, evB) =>
              val sameParking = List(
                ParkEventAttrs.ATTRIBUTE_PARKING_TAZ,
                ParkEventAttrs.ATTRIBUTE_PARKING_TYPE,
                ParkEventAttrs.ATTRIBUTE_PRICING_MODEL,
                ParkEventAttrs.ATTRIBUTE_CHARGING_TYPE
              ).forall { k =>
                evA.getAttributes.get(k).equals(evB.getAttributes.get(k))
              }
              val parkBeforeLeaving = evA.getAttributes.get("time").toDouble < evB.getAttributes
                .get("time")
                .toDouble
              sameParking && parkBeforeLeaving
          }
      }

      isSameArrivalAndDeparture shouldBe true
    }

    "Park event should be thrown after last path traversal" in {
      val parkingEvents = defaultEvents.head.filter(
        e =>
          ParkEventAttrs.EVENT_TYPE.equals(e.getEventType) || LeavingParkingEventAttrs.EVENT_TYPE
            .equals(e.getEventType)
      )

      val groupedByVehicle = parkingEvents.foldLeft(Map[String, ArrayBuffer[Event]]()) {
        case (c, ev) =>
          val vehId = ev.getAttributes.get(ParkEventAttrs.ATTRIBUTE_VEHICLE_ID)
          val array = c.getOrElse(vehId, ArrayBuffer[Event]())
          array.append(ev)
          c.updated(vehId, array)
      }

      val vehToParkLeavingEvents = groupedByVehicle.map {
        case (id, x) =>
          val (parkEvents, leavingEvents) =
            x.partition(e => ParkEventAttrs.EVENT_TYPE.equals(e.getEventType))
          (id, leavingEvents zip parkEvents)
      }

      val pathTraversalEvents =
        defaultEvents.head.filter(event => PathTraversalEvent.EVENT_TYPE.equals(event.getEventType))

      vehToParkLeavingEvents.foreach {
        case (currVehId, events) =>
          events.foreach {
            case (leavingParkEvent, parkEvent) =>
              val pathTraversalEventsInRange = pathTraversalEvents.filter { event =>
                val vehId = event.getAttributes.get(ParkEventAttrs.ATTRIBUTE_VEHICLE_ID)
                currVehId.equals(vehId) &&
                event.getTime >= leavingParkEvent.getTime &&
                event.getTime <= parkEvent.getTime
              }
              pathTraversalEventsInRange.size should be > 1
              val lastPathTravInRange = pathTraversalEventsInRange.maxBy(_.getTime)
              val indexOfLastPathTravInRange = defaultEvents.head.indexOf(lastPathTravInRange)
              val indexOfParkEvent = defaultEvents.head.indexOf(parkEvent)
              indexOfLastPathTravInRange should be < indexOfParkEvent
          }
      }
    }

    "expensive parking should reduce driving" in {
      val expensiveModeChoiceCarCount = expensiveEvents.map(filterForCarMode)
      val defaultModeChoiceCarCount = defaultEvents.map(filterForCarMode)

      println(s"Default iterations $defaultModeChoiceCarCount")
      println(s"Expensive iterations $expensiveModeChoiceCarCount")

      defaultModeChoiceCarCount
        .takeRight(5)
        .sum should be > expensiveModeChoiceCarCount.takeRight(5).sum
    }

    "empty parking access should reduce driving" in {
      val emptyModeChoiceCarCount = emptyEvents.map(filterForCarMode)
      val defaultModeChoiceCarCount = defaultEvents.map(filterForCarMode)

      println(s"Default iterations $defaultModeChoiceCarCount")
      println(s"Empty iterations $emptyModeChoiceCarCount")

      defaultModeChoiceCarCount
        .takeRight(5)
        .sum should be > emptyModeChoiceCarCount.takeRight(5).sum
    }

    "limited parking access should reduce driving" in {
      val limitedModeChoiceCarCount = limitedEvents.map(filterForCarMode)
      val defaultModeChoiceCarCount = defaultEvents.map(filterForCarMode)

      println(s"Default iterations $defaultModeChoiceCarCount")
      println(s"Limited iterations $limitedModeChoiceCarCount")

      defaultModeChoiceCarCount
        .takeRight(5)
        .sum should be > limitedModeChoiceCarCount.takeRight(5).sum

    }

    "limited parking access should increase walking distances" ignore {
      def filterPathTraversalForWalk(e: Event): Boolean = {
        PathTraversalEvent.EVENT_TYPE.equals(e.getEventType) &&
        "walk".equalsIgnoreCase(e.getAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE))
      }
      val defaultPathTraversalEvents = defaultEvents.head.filter(filterPathTraversalForWalk)

      val defaultPathLength = defaultPathTraversalEvents.foldLeft(0.0) {
        case (acc, ev) =>
          val currLength = ev.getAttributes.get(PathTraversalEvent.ATTRIBUTE_LENGTH).toDouble
          acc + currLength
      } / defaultPathTraversalEvents.size

      val limitedPathTraversalEvents = limitedEvents.head.filter(filterPathTraversalForWalk)

      val limitedPathLength = limitedPathTraversalEvents.foldLeft(0.0) {
        case (acc, ev) =>
          val currLength = ev.getAttributes.get(PathTraversalEvent.ATTRIBUTE_LENGTH).toDouble
          acc + currLength
      } / limitedPathTraversalEvents.size

      limitedPathLength should be > defaultPathLength
    }
  }
}
