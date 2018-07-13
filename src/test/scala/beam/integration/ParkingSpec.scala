package beam.integration

import beam.agentsim.events.{LeavingParkingEventAttrs, ModeChoiceEvent, ParkEventAttrs, PathTraversalEvent}
import beam.sim.BeamHelper
import com.typesafe.config.ConfigValueFactory
import org.matsim.api.core.v01.events.Event
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.core.events.handler.BasicEventHandler
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.collection.immutable.Queue
import scala.collection.mutable.ArrayBuffer

class ParkingSpec extends WordSpecLike with BeforeAndAfterAll with Matchers with BeamHelper with IntegrationSpecCommon with EventsFileHandlingCommon {

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

  def runAndCollectForIterations(parkingScenario: String, iterations: Int):  Seq[Queue[Event]] = {

    val config = baseConfig
      .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml,csv"))
      .withValue("beam.routing.transitOnStreetNetwork", ConfigValueFactory.fromAnyRef("true"))
      .withValue("beam.agentsim.taz.parking", ConfigValueFactory.fromAnyRef(s"test/input/beamville/taz-parking-${parkingScenario}.csv"))
      .withValue("beam.outputs.events.overrideWritingLevels", ConfigValueFactory.fromAnyRef("beam.agentsim.events.ParkEvent:VERBOSE, beam.agentsim.events.LeavingParkingEvent:VERBOSE, org.matsim.api.core.v01.events.ActivityEndEvent:REGULAR, org.matsim.api.core.v01.events.ActivityStartEvent:REGULAR, org.matsim.api.core.v01.events.PersonEntersVehicleEvent:REGULAR, org.matsim.api.core.v01.events.PersonLeavesVehicleEvent:REGULAR, beam.agentsim.events.ModeChoiceEvent:VERBOSE, beam.agentsim.events.PathTraversalEvent:VERBOSE"))
      .withValue("matsim.modules.controler.lastIteration", ConfigValueFactory.fromAnyRef(iterations))
      .resolve()

    val matsimConfig = runBeamWithConfig(config)._1
    val queueEvents = ArrayBuffer[Queue[Event]]()
    for(i <- 0 until iterations){
      val filePath = getEventsFilePath(matsimConfig, "xml", i).getAbsolutePath
      queueEvents.append(collectEvents(filePath))
    }
    queueEvents
  }

  lazy val limitedEvents = runAndCollectEvents("limited")
  lazy val defaultEvents = runAndCollectEvents("default")
  lazy val emptyEvents = runAndCollectEvents("empty")
  lazy val expensiveEvents = runAndCollectEvents("expensive")

  lazy val filterForCarMode: Seq[Event] => Int = { events =>
    events.filter { e =>
      val mMode = Option(e.getAttributes.get("mode"))
      e.getEventType.equals(ModeChoiceEvent.EVENT_TYPE) && mMode.exists(_.equals("car"))
    }.size
  }

  "Parking system " must {
    "guarantee at least some parking used " in {
      val parkingEvents = defaultEvents.filter(e => ParkEventAttrs.EVENT_TYPE.equals(e.getEventType))
      parkingEvents.size should be > 0
    }

    "departure and arrival should be from same parking 4 tuple" in {

      val parkingEvents = defaultEvents.filter(e => ParkEventAttrs.EVENT_TYPE.equals(e.getEventType) || LeavingParkingEventAttrs.EVENT_TYPE.equals(e.getEventType))

      val groupedByVehicle = parkingEvents.foldLeft(Map[String, ArrayBuffer[Event]]()){ case (c, ev) =>
        val vehId = ev.getAttributes.get("vehicle_id")
        val array = c.getOrElse(vehId, ArrayBuffer[Event]())
        array.append(ev)
        c.updated(vehId, array)
      }

      val res = groupedByVehicle.map{ case (id, x) =>
        val (parkEvents, leavingEvents) = x.partition(e => ParkEventAttrs.EVENT_TYPE.equals(e.getEventType))

        //First and last park events won't match
        val parkEventsWithoutLast = parkEvents.dropRight(1)
        val leavingParkEventsWithoutFirst = leavingEvents.tail

        parkEventsWithoutLast.size shouldEqual leavingParkEventsWithoutFirst.size
        (id, parkEventsWithoutLast zip leavingParkEventsWithoutFirst)
      }

      val isSameArrivalAndDeparture = res.forall{ case (_, array) =>
        array.forall{case (evA, evB) =>
          val sameParking = List(ParkEventAttrs.ATTRIBUTE_PARKING_TAZ, ParkEventAttrs.ATTRIBUTE_PARKING_TYPE,
            ParkEventAttrs.ATTRIBUTE_PRICING_MODEL, ParkEventAttrs.ATTRIBUTE_CHARGING_TYPE).forall{ k =>
            evA.getAttributes.get(k).equals(evB.getAttributes.get(k))
          }
          val parkBeforeLeaving = evA.getAttributes.get("time").toDouble < evB.getAttributes.get("time").toDouble
          sameParking && parkBeforeLeaving
        }
      }

      isSameArrivalAndDeparture shouldBe true
    }

    "Park event should be thrown after last path traversal" in {
      val parkingEvents = defaultEvents.filter(e => ParkEventAttrs.EVENT_TYPE.equals(e.getEventType) || LeavingParkingEventAttrs.EVENT_TYPE.equals(e.getEventType))

      val groupedByVehicle = parkingEvents.foldLeft(Map[String, ArrayBuffer[Event]]()){ case (c, ev) =>
        val vehId = ev.getAttributes.get("vehicle_id")
        val array = c.getOrElse(vehId, ArrayBuffer[Event]())
        array.append(ev)
        c.updated(vehId, array)
      }

      val vehToParkLeavingEvents = groupedByVehicle.map{ case (id, x) =>
        val (parkEvents, leavingEvents) = x.partition(e => ParkEventAttrs.EVENT_TYPE.equals(e.getEventType))
        (id, leavingEvents zip parkEvents)
      }

      val pathTraversalEvents = defaultEvents.filter(event => PathTraversalEvent.EVENT_TYPE.equals(event.getEventType))

      vehToParkLeavingEvents.foreach{ case (currVehId, events) =>
        events.foreach{case (leavingParkEvent, parkEvent) =>
            val pathTraversalEventsInRange = pathTraversalEvents.filter{ event =>
              val vehId = event.getAttributes.get("vehicle_id")
              currVehId.equals(vehId) &&
                event.getTime >= leavingParkEvent.getTime &&
                event.getTime <= parkEvent.getTime
            }
          pathTraversalEventsInRange.size shouldBe 2
          val lastPathTravInRange = pathTraversalEventsInRange.maxBy(_.getTime)
          val indexOfLastPathTravInRange = defaultEvents.indexOf(lastPathTravInRange)
          val indexOfParkEvent = defaultEvents.indexOf(parkEvent)
          indexOfLastPathTravInRange should be < indexOfParkEvent
        }
      }
    }

    "expensive parking should reduce driving" in {

      val iterations = 10
      val defaultIterations = runAndCollectForIterations("default", iterations)
      val expensiveIterations = runAndCollectForIterations("expensive", iterations)

      val defaultModeChoiceCarCount = defaultIterations.map(filterForCarMode)
      val expensiveModeChoiceCarCount = expensiveIterations.map(filterForCarMode)

//      println(s"Default iterations ${defaultModeChoiceCarCount}")
//      println(s"Expensive iterations ${expensiveModeChoiceCarCount}")

      defaultModeChoiceCarCount.last > expensiveModeChoiceCarCount.last shouldBe true
    }

    "empty parking access should reduce driving" in {
      val iterations = 10
      val defaultIterations = runAndCollectForIterations("default", iterations)
      val emptyIterations = runAndCollectForIterations("empty", iterations)

      val defaultModeChoiceCarCount = defaultIterations.map(filterForCarMode)
      val emptyModeChoiceCarCount = emptyIterations.map(filterForCarMode)

//      println(s"Default iterations ${defaultModeChoiceCarCount}")
//      println(s"Limited iterations ${emptyModeChoiceCarCount}")

      defaultModeChoiceCarCount.last > emptyModeChoiceCarCount.last shouldBe true
    }

    "limited parking access should reduce driving" in {
      val iterations = 10
      val defaultIterations = runAndCollectForIterations("default", iterations)
      val limitedIterations = runAndCollectForIterations("limited", iterations)

      val defaultModeChoiceCarCount = defaultIterations.map(filterForCarMode)
      val limitedModeChoiceCarCount = limitedIterations.map(filterForCarMode)

      println(s"Default iterations ${defaultModeChoiceCarCount}")
      println(s"Limited iterations ${limitedModeChoiceCarCount}")

      defaultModeChoiceCarCount.last > limitedModeChoiceCarCount.last shouldBe true

    }

    "limited parking access should increase VMT" in {
      def filterPathTraversalForCar(e: Event): Boolean = {
        PathTraversalEvent.EVENT_TYPE.equals(e.getEventType) &&
          "Car".equalsIgnoreCase(e.getAttributes.get("vehicle_type")) &&
          e.getAttributes.get("vehicle_id").matches("\\d+")
      }
      val defaultPathTraversalEvents = defaultEvents.filter(filterPathTraversalForCar)

      val defaultPathLength = defaultPathTraversalEvents.foldLeft(0.0){case (acc, ev) =>
          val currLength = ev.getAttributes.get("length").toDouble
          acc + currLength
      }

      val limitedPathTraversalEvents = limitedEvents.filter(filterPathTraversalForCar)

      val limitedPathLength = limitedPathTraversalEvents.foldLeft(0.0){case (acc, ev) =>
        val currLength = ev.getAttributes.get("length").toDouble
        acc + currLength
      }

      limitedPathLength should be > defaultPathLength
    }
  }
}
