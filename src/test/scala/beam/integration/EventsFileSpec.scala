package beam.integration

import java.io.File

import beam.sim.BeamHelper
import beam.sim.config.BeamConfig
import com.typesafe.config.{Config, ConfigValueFactory}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import scala.util.Try
import scala.xml._

/**
  * Created by fdariasm on 29/08/2017
  * 
  */

class EventsFileSpec extends FlatSpec with BeforeAndAfterAll with Matchers with BeamHelper with
  EventsFileHandlingCommon with IntegrationSpecCommon{

  private val config: Config = baseConfig
    .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml,csv"))
    .withValue("beam.routing.transitOnStreetNetwork", ConfigValueFactory.fromAnyRef("true"))
    .resolve()

  val route_input = "test/input/beamville"

  val beamConfig = BeamConfig(config)
  var matsimConfig: org.matsim.core.config.Config = null

  val exc = Try(runBeamWithConfig(config))
  val xmlFile: File = getRouteFile(beamConfig.beam.outputs.baseOutputDirectory , "xml")
  val csvFile: File = getRouteFile(beamConfig.beam.outputs.baseOutputDirectory , "csv")

  val busTripsFile = new File(s"$route_input/r5/bus/trips.txt")
  val trainTripsFile = new File(s"$route_input/r5/train/trips.txt")

  val busStopTimesFile = new File(s"$route_input/r5/bus/stop_times.txt")
  val trainStopTimesFile = new File(s"$route_input/r5/train/stop_times.txt")

  val householdsFile = XML.loadFile(s"$route_input/households.xml")

  override protected def beforeAll(): Unit = {
    matsimConfig = runBeamWithConfig(config)._1
  }



  it should "BEAM running without errors" in {
    exc.isSuccess shouldBe true
  }

  "Create xml events file in output directory" should behave like fileExists(xmlFile)

  "Create csv events file in output directory" should behave like fileExists(csvFile)

  "Events file contains all bus routes" should behave like containsAllBusRoutes(busTripsFile, xmlFile, new ReadEventsBeam)

  "Events file contains all train routes" should behave like containsAllTrainRoutes(trainTripsFile, xmlFile, new ReadEventsBeam)

  "Events file contains exactly the same bus trips entries" should behave like containsExactlyBusRoutes(busTripsFile, xmlFile, new ReadEventsBeam)

  "Events file contains exactly the same train trips entries" should behave like containsExactlyTrainRoutes(trainTripsFile, xmlFile, new ReadEventsBeam)

  "Events file contains same pathTraversal defined at stop times file for bus input file" should behave like containsSameBusEntriesPathTraversal(busStopTimesFile,xmlFile,new ReadEventsBeam)

  "Events file contains same pathTraversal defined at stop times file for train input file" should behave like containsSameTrainEntriesPathTraversal(trainStopTimesFile,xmlFile,new ReadEventsBeam)

  //"Events file contains an events sequence correct" should behave like sequenceOfEventsIsCorrect(xmlFile, new ReadEventsBeam)

  "Events file make sure car used is owned by household" should behave like carUsedIsOwnedHousehold(householdsFile,xmlFile, new ReadEventsBeam)



  private def containsAllBusRoutes(routesFile: File, eventsFile: File, eventsReader: ReadEvents) = {
    it should " contain all bus routes" in {
      val listTrips = getListIDsWithTag(routesFile, "route_id", 2).sorted
      val listValueTagEventFile = eventsReader.getListTagsFromFile(eventsFile,Some("vehicle_type", "bus"),"vehicle_id").groupBy(identity)
      listValueTagEventFile.size shouldBe listTrips.size
    }
  }

  private def containsAllTrainRoutes(routesFile: File, eventsFile: File, eventsReader: ReadEvents) = {
    it should " contain all bus routes" in {
      val listTrips = getListIDsWithTag(routesFile, "route_id", 2).sorted
      val listValueTagEventFile = eventsReader.getListTagsFromFile(eventsFile,Some("vehicle_type", "subway"),"vehicle_id").groupBy(identity)
      listValueTagEventFile.size shouldBe listTrips.size
    }
  }

  private def containsExactlyBusRoutes(routesFile: File, eventsFile: File, eventsReader: ReadEvents) = {
    it should "contain the same bus trips entries" in {
      val listTrips = getListIDsWithTag(routesFile, "route_id", 2).sorted
      val listValueTagEventFile = eventsReader.getListTagsFromFile(eventsFile, Some("vehicle_type", "bus"), "vehicle_id").groupBy(identity).keys.toSeq
      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
      listTripsEventFile shouldBe listTrips
    }
  }

  private def containsExactlyTrainRoutes(routesFile: File, eventsFile: File, eventsReader: ReadEvents) = {
    it should "contain the same train trips entries" in {
      val listTrips = getListIDsWithTag(routesFile, "route_id", 2).sorted
      val listValueTagEventFile = eventsReader.getListTagsFromFile(eventsFile,Some("vehicle_type", "subway"),"vehicle_id").groupBy(identity).keys.toSeq
      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
      listTripsEventFile shouldBe listTrips
    }
  }


  private def containsSameBusEntriesPathTraversal(routesFile: File, eventsFile: File, eventsReader: ReadEvents) ={
    it should "contain same pathTraversal defined at stop times file for bus input file" in {
      val listTrips = getListIDsWithTag(routesFile, "trip_id", 0).sorted
      val grouped = listTrips.groupBy(identity)
      val groupedWithCount = grouped.map{case (k, v) => (k, v.size - 1)}
      val listValueTagEventFile = eventsReader.getListTagsFromFile(eventsFile, Some("vehicle_type", "bus"),"vehicle_id", Some("PathTraversal"))
      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1))
      val groupedXml = listTripsEventFile.groupBy(identity)
      val groupedXmlWithCount = groupedXml.map{case (k,v) => (k, v.size)}

      groupedXmlWithCount should contain theSameElementsAs groupedWithCount
    }
  }

  private def containsSameTrainEntriesPathTraversal(routesFile: File, eventsFile: File, eventsReader: ReadEvents) ={
    it should "contain same pathTraversal defined at stop times file for train input file" in {
      val listTrips = getListIDsWithTag(routesFile, "trip_id", 0).sorted
      val grouped = listTrips.groupBy(identity)
      val groupedWithCount = grouped.map{case (k, v) => (k, v.size - 1)}
      val listValueTagEventFile = eventsReader.getListTagsFromFile(eventsFile, Some("vehicle_type", "subway"),"vehicle_id", Some("PathTraversal"))

      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
      val groupedXml = listTripsEventFile.groupBy(identity)
      val groupedXmlWithCount = groupedXml.map{case (k,v) => (k, v.size)}

      groupedXmlWithCount should contain theSameElementsAs groupedWithCount
    }
  }

  private def sequenceOfEventsIsCorrect (eventsFile: File, eventsReader: ReadEvents) = {

    it should "event time is in ascending sequence " in {
      val listValueTagEventFile = eventsReader.getListTagsFromFile(eventsFile, tagToReturn="time")

      listValueTagEventFile shouldBe sorted

    }

  }

  private def carUsedIsOwnedHousehold (householdsFile: Elem,eventsFile: File, eventsReader: ReadEvents) = {

    it should "car used is owned by household " in {

      val householdSeq = householdsFile\\ "household"


      val householdMap = householdSeq
        .map(n=> n.attribute("id").get.toString() -> ((n \\ "members" \\ "personId")
          .map(_.attribute("refId").get.toString())  , (n \\ "vehicles" \\ "vehicleDefinitionId")
          .map(_.attribute("refId").get.toString())))
        .toMap

      val events = eventsReader.getListTwoTagsFromFile(eventsFile, Some("type", "PersonEntersVehicle"),tagToReturn="person",tagTwoToReturn = Some("vehicle"));


      //val temo = householdMap.find{case (_, seqPersons,_ ) =>  seqPersons}
      println(events);

      println(householdMap);





      //val mapVehiclePersona =
      //val listValueTagEventFile = eventsReader.getListTagsFromFile(eventsFile, tagToReturn="PersonEntersVehicle")

      //listValueTagEventFile shouldBe sorted
      //
      //TODO

    }

  }

  /**

  it should "contain all bus routes" in {
    val listTrips = getListIDsWithTag(new File("test/input/beamville/r5/bus/trips.txt"), "route_id", 2).sorted
    val listValueTagEventFile = new ReadEventsBeam().getListTagsFromFile(getEventsFilePath(matsimConfig , "xml"),Some("vehicle_type", "bus"),"vehicle_id").groupBy(identity)
    listValueTagEventFile.size shouldBe listTrips.size
  }

  it should "contain all train routes" in {
    val listTrips = getListIDsWithTag(new File("test/input/beamville/r5/train/trips.txt"), "route_id", 2).sorted
    val listValueTagEventFile = new ReadEventsBeam().getListTagsFromFile(getEventsFilePath(matsimConfig , "xml"), Some("vehicle_type", "subway"),"vehicle_id").groupBy(identity)
    listValueTagEventFile.size shouldBe listTrips.size
  }

  it should "contain the same bus trips entries" in {
    val listTrips = getListIDsWithTag(new File("test/input/beamville/r5/bus/trips.txt"), "route_id", 2).sorted
    val listValueTagEventFile = new ReadEventsBeam().getListTagsFromFile(getEventsFilePath(matsimConfig , "xml"), Some("vehicle_type", "bus"), "vehicle_id").groupBy(identity).keys.toSeq
    val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
    listTripsEventFile shouldBe listTrips
  }

  it should "contain the same train trips entries" in {
    val listTrips = getListIDsWithTag(new File("test/input/beamville/r5/train/trips.txt"), "route_id", 2).sorted
    val listValueTagEventFile = new ReadEventsBeam().getListTagsFromFile(getEventsFilePath(matsimConfig , "xml"),Some("vehicle_type", "subway"),"vehicle_id").groupBy(identity).keys.toSeq
    val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
    listTripsEventFile shouldBe listTrips
  }

  it should "contain same pathTraversal defined at stop times file for bus input file" ignore {
    val listTrips = getListIDsWithTag(new File("test/input/beamville/r5/bus/stop_times.txt"), "trip_id", 0).sorted
    val grouped = listTrips.groupBy(identity)
    val groupedWithCount = grouped.map{case (k, v) => (k, v.size)}
    val listValueTagEventFile = new ReadEventsBeam().getListTagsFromFile(getEventsFilePath(matsimConfig, "xml"), Some("vehicle_type", "bus"),"vehicle_id", Some("PathTraversal"))
    val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1))
    val groupedXml = listTripsEventFile.groupBy(identity)
    val groupedXmlWithCount = groupedXml.map{case (k,v) => (k, v.size)}
    groupedXmlWithCount should contain theSameElementsAs groupedWithCount
  }

  it should "contain same pathTraversal defined at stop times file for train input file" ignore {
    val listTrips = getListIDsWithTag(new File("test/input/beamville/r5/train/stop_times.txt"), "trip_id", 0).sorted
    val grouped = listTrips.groupBy(identity)
    val groupedWithCount = grouped.map{case (k, v) => (k, v.size)}
    val listValueTagEventFile = new ReadEventsBeam().getListTagsFromFile(getEventsFilePath(matsimConfig , "xml"), Some("vehicle_type", "subway"),"vehicle_id", Some("PathTraversal"))
    val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
    val groupedXml = listTripsEventFile.groupBy(identity)
    val groupedXmlWithCount = groupedXml.map{case (k,v) => (k, v.size)}
    groupedXmlWithCount should contain theSameElementsAs groupedWithCount
  }

    */

  it should "also be available as csv file" in {
    assert(getEventsFilePath(matsimConfig, "csv").exists())
  }


  private def fileExists(file: File) = {
    it should " exists in output directory" in {
      file.exists() shouldBe true
    }
  }

}

