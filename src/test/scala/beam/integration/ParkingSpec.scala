package beam.integration

import beam.agentsim.events.{LeavingParkingEventAttrs, ParkEventAttrs}
import beam.sim.BeamHelper
import com.typesafe.config.ConfigValueFactory
import org.matsim.api.core.v01.events.Event
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.core.events.handler.BasicEventHandler
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.collection.immutable.Queue
import scala.collection.mutable.ArrayBuffer
import scala.util.Try

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
    val config = baseConfig
      .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml,csv"))
      .withValue("beam.routing.transitOnStreetNetwork", ConfigValueFactory.fromAnyRef("true"))
      .withValue("beam.agentsim.taz.parking", ConfigValueFactory.fromAnyRef(s"test/input/beamville/taz-parking-${parkingScenario}.csv"))
      .withValue("beam.outputs.events.overrideWritingLevels", ConfigValueFactory.fromAnyRef("beam.agentsim.events.ParkEvent:VERBOSE, beam.agentsim.events.LeavingParkingEvent:VERBOSE, org.matsim.api.core.v01.events.ActivityEndEvent:REGULAR, org.matsim.api.core.v01.events.ActivityStartEvent:REGULAR, org.matsim.api.core.v01.events.PersonEntersVehicleEvent:REGULAR, org.matsim.api.core.v01.events.PersonLeavesVehicleEvent:REGULAR, beam.agentsim.events.ModeChoiceEvent:VERBOSE, beam.agentsim.events.PathTraversalEvent:VERBOSE"))
      .resolve()

    val matsimConfig = runBeamWithConfig(config)._1

    val filePath = getEventsFilePath(matsimConfig, "xml").getAbsolutePath
    println(s"Events filePath $filePath")

    collectEvents(filePath)
  }

  val defaultEvents = runAndCollectEvents("default")
//  val emptyEvents = runAndCollectEvents("empty")
//  val expensiveEvents = runAndCollectEvents("expensive")
//  val limitedEvents = runAndCollectEvents("limited")

  "Parking system " must {
    "guarantee at least some parking used " in {
      val parkingEvents = defaultEvents.filter(e => ParkEventAttrs.EVENT_TYPE.equals(e.getEventType))
      parkingEvents.size should be > 0
    }

    "arrival and departure should be from same parking 4 tuple" in {

      val parkingEvents = defaultEvents.filter(e => ParkEventAttrs.EVENT_TYPE.equals(e.getEventType) || LeavingParkingEventAttrs.EVENT_TYPE.equals(e.getEventType))

      val parkEvents = defaultEvents.count(e => ParkEventAttrs.EVENT_TYPE.equals(e.getEventType))
      val leavingParkEvents = defaultEvents.count(e => LeavingParkingEventAttrs.EVENT_TYPE.equals(e.getEventType))

      val noCars = defaultEvents
        .map(e => Option(e.getAttributes.get("vehicle_id")))
        .filter(_.isDefined)
        .map(e => Try(e.get.toInt))
        .filter(_.isSuccess)
        .map(_.get)
        .toSet
        .size

      println(s"Total cars: $noCars")
      println(s"ParkEvents: $parkEvents")
      println(s"LeavingParkEvents: $leavingParkEvents")

      val groupedByVehicle = parkingEvents.foldLeft(Map[String, ArrayBuffer[Event]]()){ case (c, ev) =>
        val vehId = ev.getAttributes.get("vehicle_id")
        val array = c.getOrElse(vehId, ArrayBuffer[Event]())
        array.append(ev)
        c.updated(vehId, array)
      }

      val res = groupedByVehicle.map{ case (id, x) =>
        val (parkEvents, leavingEvents) = x.partition(e => ParkEventAttrs.EVENT_TYPE.equals(e.getEventType))

        parkEvents.size shouldEqual leavingEvents.size
        (id, parkEvents zip leavingEvents)
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

//    "expensive parking should reduce driving" in {
//      val parkingEvents = defaultEvents.filter(e => ParkEventAttrs.EVENT_TYPE.equals(e.getEventType))
//      val emptyParkingEvents = emptyEvents.filter(e => ParkEventAttrs.EVENT_TYPE.equals(e.getEventType))
//
//      parkingEvents.size should be > emptyParkingEvents.size
//    }
//
//    "limited parking access should reduce driving" in {
//      val parkingEvents = defaultEvents.filter(e => ParkEventAttrs.EVENT_TYPE.equals(e.getEventType))
//      val emptyParkingEvents = emptyEvents.filter(e => ParkEventAttrs.EVENT_TYPE.equals(e.getEventType))
//
//      parkingEvents.size should be > emptyParkingEvents.size
//    }

    "when parking expensive then after several iterations we expect fewer people to choose drive mode due to poor experiences" in {

//      val parkingScenario = "expensive"
//      val iterations = 2
//
//      val config = baseConfig
//        .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml,csv"))
//        .withValue("beam.routing.transitOnStreetNetwork", ConfigValueFactory.fromAnyRef("true"))
//        .withValue("beam.agentsim.taz.parking", ConfigValueFactory.fromAnyRef(s"test/input/beamville/taz-parking-${parkingScenario}.csv"))
//        .withValue("beam.outputs.events.overrideWritingLevels", ConfigValueFactory.fromAnyRef("beam.agentsim.events.ParkEvent:VERBOSE, beam.agentsim.events.LeavingParkingEvent:VERBOSE"))
//        .withValue("matsim.modules.controler.firstIteration", ConfigValueFactory.fromAnyRef(iterations))
//        .resolve()
//
//      val matsimConfig = runBeamWithConfig(config)._1
//      val queueEvents = ArrayBuffer[Queue[Event]]()
//      for(i <- 0 to iterations){
//        val filePath = getEventsFilePath(matsimConfig, "xml", i).getAbsolutePath
//        queueEvents.append(collectEvents(filePath))
//      }
//
//      val queueParkingEvents = queueEvents.map(_.filter(e => ParkEventAttrs.EVENT_TYPE.equals(e.getEventType)).size)
//      (queueParkingEvents.dropRight(1) zip queueParkingEvents.tail).forall{ case (prev, next) =>
//          prev < next
//      } shouldBe true

      pending
    }

  }
}
