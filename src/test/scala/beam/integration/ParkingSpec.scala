package beam.integration

import java.io.File

import beam.agentsim.events.{LeavingParkingEvent, ModeChoiceEvent, ParkingEvent, PathTraversalEvent}
import beam.sim.BeamHelper
import beam.utils.EventReader
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.apache.commons.io.FileUtils
import org.matsim.api.core.v01.events.Event
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.collection.mutable.ArrayBuffer

class ParkingSpec extends WordSpecLike with BeforeAndAfterAll with Matchers with BeamHelper with IntegrationSpecCommon {

  def runAndCollectEvents(parkingScenario: String): Seq[Event] = {
    runAndCollectForIterations(parkingScenario, 1).head
  }

  def runAndCollectForIterations(parkingScenario: String, iterations: Int): Seq[Seq[Event]] = {
    val param = ConfigFactory.parseString(
      """
        |{
        | matsim.modules.strategy.parameterset = [
        |   {type = strategysettings, disableAfterIteration = -1, strategyName = ClearRoutes , weight = 0.7},
        |   {type = strategysettings, disableAfterIteration = -1, strategyName = ClearModes , weight = 0.0}
        |   {type = strategysettings, disableAfterIteration = -1, strategyName = TimeMutator , weight = 0.0},
        |   {type = strategysettings, disableAfterIteration = -1, strategyName = SelectExpBeta , weight = 0.3},
        | ]
        |}
      """.stripMargin
    )

    val config = baseConfig
      .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml,csv"))
      .withValue(
        TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
        ConfigValueFactory.fromAnyRef(TestConstants.MODE_CHOICE_MULTINOMIAL_LOGIT)
      )
      .withValue(
        "beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.car_intercept",
        ConfigValueFactory.fromAnyRef(1.0)
      )
      .withValue(
        "beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.walk_transit_intercept",
        ConfigValueFactory.fromAnyRef(0.0)
      )
      .withValue(
        "beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.drive_transit_intercept",
        ConfigValueFactory.fromAnyRef(0.0)
      )
      .withValue(
        "beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_transit_intercept",
        ConfigValueFactory.fromAnyRef(0.0)
      )
      .withValue(
        "beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_intercept",
        ConfigValueFactory.fromAnyRef(0.0)
      )
      .withValue(
        "beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.walk_intercept",
        ConfigValueFactory.fromAnyRef(-5.0)
      )
      .withValue(
        "beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.bike_intercept",
        ConfigValueFactory.fromAnyRef(0.0)
      )
      .withValue("matsim.modules.strategy.ModuleProbability_1", ConfigValueFactory.fromAnyRef(0.3))
      .withValue("matsim.modules.strategy.ModuleProbability_2", ConfigValueFactory.fromAnyRef(0.7))
      .withValue(
        "beam.agentsim.taz.parkingFilePath",
        ConfigValueFactory.fromAnyRef(s"test/input/beamville/parking/taz-parking-$parkingScenario.csv")
      )
      .withValue(
        "beam.outputs.events.overrideWritingLevels",
        ConfigValueFactory.fromAnyRef(
          "beam.agentsim.events.ParkEvent:VERBOSE, beam.agentsim.events.LeavingParkingEvent:VERBOSE, org.matsim.api.core.v01.events.ActivityEndEvent:REGULAR, org.matsim.api.core.v01.events.ActivityStartEvent:REGULAR, org.matsim.api.core.v01.events.PersonEntersVehicleEvent:REGULAR, org.matsim.api.core.v01.events.PersonLeavesVehicleEvent:REGULAR, beam.agentsim.events.ModeChoiceEvent:VERBOSE, beam.agentsim.events.PathTraversalEvent:VERBOSE"
        )
      )
      .withValue(
        "beam.agentsim.lastIteration",
        ConfigValueFactory.fromAnyRef(iterations)
      )
      .withValue(
        "matsim.modules.controler.lastIteration",
        ConfigValueFactory.fromAnyRef(iterations)
      )
      .withFallback(param)
      .resolve()

    val (matsimConfig, outputDirectory, _) = runBeamWithConfig(config)

    val queueEvents = ArrayBuffer[Seq[Event]]()
    for (i <- 0 until iterations) {
      val filePath = EventReader.getEventsFilePath(matsimConfig, "events", "xml", i).getAbsolutePath
      queueEvents.append(EventReader.fromXmlFile(filePath))
    }

    val outputDirectoryFile = new File(outputDirectory)
    FileUtils.copyDirectory(outputDirectoryFile, new File(s"${outputDirectory}_$parkingScenario"))

    queueEvents
  }

  private lazy val defaultEvents = runAndCollectForIterations("default", 5)

  val countForPathTraversalAndCarMode: Seq[Event] => Int = { events =>
    events.count { e =>
      val mMode = Option(e.getAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE))
      e.getEventType.equals(ModeChoiceEvent.EVENT_TYPE) && mMode.exists(_.equals("car"))
    }
  }

  val countForModeChoiceAndCarMode: Seq[Event] => Int = { events =>
    events.count { e =>
      val mMode = Option(e.getAttributes.get(ModeChoiceEvent.ATTRIBUTE_MODE))
      e.getEventType.equals(ModeChoiceEvent.EVENT_TYPE) && mMode.exists(_.equals("car"))
    }
  }

  "Parking system " must {
    "guarantee at least some parking used " in {
      val parkingEvents =
        defaultEvents.head.filter(e => ParkingEvent.EVENT_TYPE.equals(e.getEventType))
      parkingEvents.size should be > 0
    }

    "departure and arrival should be from same parking 4 tuple" in {

      val parkingEvents =
        defaultEvents.head.filter(x => x.isInstanceOf[ParkingEvent] || x.isInstanceOf[LeavingParkingEvent])
      val groupedByVehicle = parkingEvents.foldLeft(Map[String, ArrayBuffer[Event]]()) {
        case (c, ev) =>
          val vehId = ev.getAttributes.get(ParkingEvent.ATTRIBUTE_VEHICLE_ID)
          val array = c.getOrElse(vehId, ArrayBuffer[Event]())
          array.append(ev)
          c.updated(vehId, array)
      }

      val res = groupedByVehicle.map {
        case (id, x) =>
          val (parkEvents, leavingEvents) =
            x.partition(e => ParkingEvent.EVENT_TYPE.equals(e.getEventType))

          // First and last park events won't match
          val parkEventsWithoutLast = parkEvents.dropRight(1)
          val leavingParkEventsWithoutFirst = leavingEvents.tail

          parkEventsWithoutLast.size shouldEqual leavingParkEventsWithoutFirst.size
          (id, parkEventsWithoutLast zip leavingParkEventsWithoutFirst)
      }

      res.collect {
        case (_, array) =>
          array.foreach {
            case (evA, evB) =>
              List(
                ParkingEvent.ATTRIBUTE_PARKING_TAZ,
                ParkingEvent.ATTRIBUTE_PARKING_TYPE,
                ParkingEvent.ATTRIBUTE_PRICING_MODEL,
                ParkingEvent.ATTRIBUTE_CHARGING_TYPE
              ).foreach { k =>
                evA.getAttributes.get(k) should equal(evB.getAttributes.get(k))
              }
              evA.getAttributes.get("time").toDouble should be <= evB.getAttributes
                .get("time")
                .toDouble
          }
      }
    }

    "Park event should be thrown after last path traversal" in {
      val parkingEvents =
        defaultEvents.head.filter(x => x.isInstanceOf[ParkingEvent] || x.isInstanceOf[LeavingParkingEvent])

      val groupedByVehicle = parkingEvents.foldLeft(Map[String, ArrayBuffer[Event]]()) {
        case (c, ev) =>
          val vehId = ev.getAttributes.get(ParkingEvent.ATTRIBUTE_VEHICLE_ID)
          val array = c.getOrElse(vehId, ArrayBuffer[Event]())
          array.append(ev)
          c.updated(vehId, array)
      }

      val vehToParkLeavingEvents = groupedByVehicle.map {
        case (id, x) =>
          val (parkEvents, leavingEvents) =
            x.partition(e => ParkingEvent.EVENT_TYPE.equals(e.getEventType))
          (id, leavingEvents zip parkEvents)
      }

      val pathTraversalEvents =
        defaultEvents.head.filter(event => PathTraversalEvent.EVENT_TYPE.equals(event.getEventType))

      vehToParkLeavingEvents.foreach {
        case (currVehId, events) =>
          events.foreach {
            case (leavingParkEvent, parkEvent) =>
              val pathTraversalEventsInRange = pathTraversalEvents.filter { event =>
                val vehId = event.getAttributes.get(ParkingEvent.ATTRIBUTE_VEHICLE_ID)
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

    "very expensive parking should reduce driving" in {
      val expensiveEvents = runAndCollectForIterations("very-expensive", 5)

      val expensiveModeChoiceCarCount = expensiveEvents.map(countForPathTraversalAndCarMode)
      val defaultModeChoiceCarCount = defaultEvents.map(countForPathTraversalAndCarMode)

      logger.debug("Default iterations ", defaultModeChoiceCarCount.mkString(","))
      logger.debug("Expensive iterations ", expensiveModeChoiceCarCount.mkString(","))

      defaultModeChoiceCarCount
        .takeRight(5)
        .sum should be > expensiveModeChoiceCarCount.takeRight(5).sum
    }

    "no parking stalls should reduce driving" in {
      val emptyEvents = runAndCollectForIterations("empty", 5)

      val emptyModeChoiceCarCount = emptyEvents.map(countForPathTraversalAndCarMode)
      val defaultModeChoiceCarCount = defaultEvents.map(countForPathTraversalAndCarMode)

      logger.debug("Default iterations", defaultModeChoiceCarCount.mkString(","))
      logger.debug("Empty iterations", emptyModeChoiceCarCount.mkString(","))

      defaultModeChoiceCarCount
        .takeRight(5)
        .sum should be > emptyModeChoiceCarCount.takeRight(5).sum
    }
  }

}
