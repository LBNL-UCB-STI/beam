package beam.integration

import java.io.File

import beam.sim.RunBeam
import beam.sim.config.{BeamConfig, ConfigModule}
import com.typesafe.config.{Config, ConfigValueFactory}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

/**
  * Created by fdariasm on 29/08/2017
  * 
  */

class EventsFileSpec extends FlatSpec with BeforeAndAfterAll with Matchers with RunBeam with
  EventsFileHandlingCommon with IntegrationSpecCommon{

  private val config: Config = baseConfig
    .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml,csv"))
    .resolve()

  val beamConfig = BeamConfig(config)

  override protected def beforeAll(): Unit = {
    runBeamWithConfig(config)
    super.beforeAll()
  }

  def xmlFile: File = getRouteFile(beamConfig.beam.outputs.outputDirectory , "xml")
  def csvFile: File = getRouteFile(beamConfig.beam.outputs.outputDirectory , "csv")
  def busTripsFile = new File(s"${beamConfig.beam.inputDirectory}/r5/bus/trips.txt")
  def trainTripsFile = new File(s"${beamConfig.beam.inputDirectory}/r5/train/trips.txt")
  def busStopTimesFile = new File(s"${beamConfig.beam.inputDirectory}/r5/bus/stop_times.txt")
  def trainStopTimesFile = new File(s"${beamConfig.beam.inputDirectory}/r5/train/stop_times.txt")

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

