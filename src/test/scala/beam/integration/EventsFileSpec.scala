package beam.integration

import java.io.File

import beam.sim.RunBeam
import beam.sim.config.ConfigModule
import org.scalatest.{FlatSpec, Matchers}

import scala.util.Try

/**
  * Created by fdariasm on 29/08/2017
  * 
  */

class EventsFileSpec extends FlatSpec with Matchers with RunBeam with
  EventsFileHandlingCommon with IntegrationSpecCommon{
  lazy val configFileName = Some(s"${System.getenv("PWD")}/test/input/beamville/beam.conf")

  lazy val beamConfig = customBeam(configFileName, eventsFileOutputFormats = Some("xml,csv"))

  val exc = Try(runBeamWithConfig(beamConfig, ConfigModule.matSimConfig))
  val xmlFile: File = getRouteFile(beamConfig.beam.outputs.outputDirectory , "xml")
  val csvFile: File = getRouteFile(beamConfig.beam.outputs.outputDirectory , "csv")
  lazy val route_input = beamConfig.beam.inputDirectory

//  val xmlEventsReader: ReadEvents = new ReadEventsBeam
//  val csvEventsReader: ReadEvents = ???

  val busTripsFile = new File(s"$route_input/r5/bus/trips.txt")
  val trainTripsFile = new File(s"$route_input/r5/train/trips.txt")

  val busStopTimesFile = new File(s"$route_input/r5/bus/stop_times.txt")
  val trainStopTimesFile = new File(s"$route_input/r5/train/stop_times.txt")

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

  private def fileExists(file: File) = {
    it should " exists in output directory" in {
      file.exists() shouldBe true
    }
  }

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
    it should "contain same pathTraversal defined at stop times file for bus input file" ignore {
      val listTrips = getListIDsWithTag(routesFile, "trip_id", 0).sorted
      val grouped = listTrips.groupBy(identity)
      val groupedWithCount = grouped.map{case (k, v) => (k, v.size)}
      val listValueTagEventFile = eventsReader.getListTagsFromFile(eventsFile, Some("vehicle_type", "bus"),"vehicle_id", Some("PathTraversal"))
      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1))
      val groupedXml = listTripsEventFile.groupBy(identity)
      val groupedXmlWithCount = groupedXml.map{case (k,v) => (k, v.size)}

      groupedXmlWithCount should contain theSameElementsAs groupedWithCount
    }
  }

  private def containsSameTrainEntriesPathTraversal(routesFile: File, eventsFile: File, eventsReader: ReadEvents) ={
    it should "contain same pathTraversal defined at stop times file for train input file" ignore {
      val listTrips = getListIDsWithTag(routesFile, "trip_id", 0).sorted
      val grouped = listTrips.groupBy(identity)
      val groupedWithCount = grouped.map{case (k, v) => (k, v.size)}
      val listValueTagEventFile = eventsReader.getListTagsFromFile(eventsFile, Some("vehicle_type", "subway"),"vehicle_id", Some("PathTraversal"))

      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
      val groupedXml = listTripsEventFile.groupBy(identity)
      val groupedXmlWithCount = groupedXml.map{case (k,v) => (k, v.size)}

      groupedXmlWithCount should contain theSameElementsAs groupedWithCount
    }
  }

}

