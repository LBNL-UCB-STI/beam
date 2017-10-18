package beam.integration

import java.io.{BufferedReader, File, FileInputStream, InputStreamReader}
import java.util.zip.GZIPInputStream

import beam.sim.RunBeam
import beam.sim.config.{BeamConfig, ConfigModule}
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.util.Try

/**
  * Created by fdariasm on 29/08/2017
  * 
  */

trait EventsFileHandlingCommon {
  def beamConfig: BeamConfig
  //Obtains name of latest created folder
  //Assumes that dir is a directory known to exist
  def getListOfSubDirectories(dir: File): String = {
    val simName = beamConfig.beam.agentsim.simulationName
    val prefix = s"${simName}_"
    dir.listFiles
      .filter(s => s.isDirectory && s.getName.startsWith(prefix))
      .map(_.getName)
      .toList
      .sorted
      .reverse
      .head
  }

  def getListIDsWithTag(file: File, tagIgnore: String, positionID: Int): List[String] = {
    var listResult = List[String]()
    for (line <- Source.fromFile(file.getPath).getLines) {
      if (!line.startsWith(tagIgnore)) {
        listResult = line.split(",")(positionID) :: listResult

      }
    }

    return listResult

  }

  def getListTagsFromXml(file: File, stringContain: String, tagContain: String): List[String] = {
    var listResult = List[String]()
    for (line <- Source.fromFile(file.getPath).getLines) {
      if (line.contains(stringContain)) {
        val temp = scala.xml.XML.loadString(line)
        val value = temp.attributes(tagContain).toString
        listResult = value:: listResult

      }
    }

    return  listResult
  }

  def getRouteFile(route_output: String, extension: String): File = {
    val route = s"$route_output/${getListOfSubDirectories(new File(route_output))}/ITERS/it.0/0.events.$extension"
    new File(route)
  }

  def getEventsReader(beamConfig: BeamConfig): ReadEvents = {
    beamConfig.beam.outputs.eventsFileOutputFormats match{
      case "xml" => new ReadEventsXml
      case "csv" => ???
      case "xml.gz" => new ReadEventsXMlGz
      case "csv.gz" => ???
      case _ => throw new RuntimeException("Unsupported format")
    }
  }

}

trait ReadEvents{
  def getListTagsFrom(file: File, stringContain: String, tagContain: String): scala.List[String]
  def getLinesFrom(file: File): String
}

class ReadEventsXml extends ReadEvents {


  def getLinesFrom(file: File): String = {
    Source.fromFile(file.getPath).getLines.mkString
  }

  def getListTagsFrom(file: File, stringContain: String, tagContain: String): scala.List[String] = {
    getListTagsFromLines(Source.fromFile(file.getPath).getLines.toList, stringContain, tagContain)
  }

  def getListTagsFromLines(file_lines: List[String], stringContain: String, tagContain: String): scala.List[String] = {
    var listResult = List[String]()
    for (line <- file_lines) {
      if (line.contains(stringContain)) {
        val temp = scala.xml.XML.loadString(line)
        val value = temp.attributes(tagContain).toString
        listResult = value:: listResult
      }
    }
    return  listResult
  }
}

class ReadEventsXMlGz extends ReadEventsXml {
  def extractGzFile(file: File): scala.List[String] = {
    val fin = new FileInputStream(new java.io.File(file.getPath))
    val gzis = new GZIPInputStream(fin)
    val reader =new BufferedReader(new InputStreamReader(gzis, "UTF-8"))

    var lines = new ListBuffer[String]

    while(reader.ready()){
      lines += reader.readLine()
    }
    return  lines.toList

  }
  override def getListTagsFrom(file: File, stringContain: String, tagContain: String): scala.List[String] = {
    getListTagsFromLines(extractGzFile(file), stringContain, tagContain)
  }

  override def getLinesFrom(file: File): String = {
    extractGzFile(file).mkString
  }
}

trait EventsFileBehaviors { this: FlatSpec with Matchers with RunBeam with EventsFileHandlingCommon =>
  def fileExists(file: File) = {
    it should " exists in output directory" in {
      file.exists() shouldBe true
    }
  }

  def containsAllBusRoutes(routesFile: File, eventsFile: File, eventsReader: ReadEvents) = {
    it should " contain all bus routes" ignore {
      val listTrips = getListIDsWithTag(routesFile, "route_id", 2).sorted
      val listValueTagEventFile = eventsReader.getListTagsFrom(eventsFile,"person=\"TransitDriverAgent-bus.gtfs","vehicle")
      listValueTagEventFile.size shouldBe listTrips.size
    }
  }

  def containsAllTrainRoutes(routesFile: File, eventsFile: File, eventsReader: ReadEvents) = {
    it should " contain all bus routes" ignore {
      val listTrips = getListIDsWithTag(routesFile, "route_id", 2).sorted
      val listValueTagEventFile = eventsReader.getListTagsFrom(eventsFile,"person=\"TransitDriverAgent-train.gtfs","vehicle")
      listValueTagEventFile.size shouldBe listTrips.size
    }
  }

  def containsExactlyBusRoutes(routesFile: File, eventsFile: File, eventsReader: ReadEvents) = {
    it should "contain the same bus trips entries" ignore {
      val listTrips = getListIDsWithTag(routesFile, "route_id", 2).sorted
      val listValueTagEventFile = eventsReader.getListTagsFrom(eventsFile,"person=\"TransitDriverAgent-bus.gtfs","vehicle")
      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
      listTripsEventFile shouldBe listTrips
    }
  }

  def containsExactlyTrainRoutes(routesFile: File, eventsFile: File, eventsReader: ReadEvents) = {
    it should "contain the same train trips entries" ignore {
      val listTrips = getListIDsWithTag(routesFile, "route_id", 2).sorted
      val listValueTagEventFile = eventsReader.getListTagsFrom(eventsFile,"person=\"TransitDriverAgent-train.gtfs","vehicle")
      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
      listTripsEventFile shouldBe listTrips
    }
  }


  def containsSameBusEntriesPathTraversal(routesFile: File, eventsFile: File, eventsReader: ReadEvents) ={

    it should "contain same pathTraversal defined at stop times file for bus input file" ignore {
      val listTrips = getListIDsWithTag(routesFile, "trip_id", 0).sorted
      val grouped = listTrips.groupBy(identity)
      val groupedWithCount = grouped.map{case (k, v) => (k, v.size)}
      val listValueTagEventFile = eventsReader.getListTagsFrom(eventsFile,"type=\"PathTraversal\" vehicle_id=\"bus:","vehicle_id")

      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
      val groupedXml = listTripsEventFile.groupBy(identity)
      val groupedXmlWithCount = groupedXml.map{case (k,v) => (k, v.size)}


      groupedXmlWithCount should contain theSameElementsAs groupedWithCount
    }
  }

  def containsSameTrainEntriesPathTraversal(routesFile: File, eventsFile: File, eventsReader: ReadEvents) ={
    it should "contain same pathTraversal defined at stop times file for train input file" ignore {
      val listTrips = getListIDsWithTag(routesFile, "trip_id", 0).sorted
      val grouped = listTrips.groupBy(identity)
      val groupedWithCount = grouped.map{case (k, v) => (k, v.size)}
      val listValueTagEventFile = eventsReader.getListTagsFrom(eventsFile,"type=\"PathTraversal\" vehicle_id=\"train:","vehicle_id")

      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
      val groupedXml = listTripsEventFile.groupBy(identity)
      val groupedXmlWithCount = groupedXml.map{case (k,v) => (k, v.size)}


      groupedXmlWithCount should contain theSameElementsAs groupedWithCount
    }
  }
}

class EventsFileSpec extends FlatSpec with Matchers with RunBeam with EventsFileHandlingCommon with EventsFileBehaviors {
  lazy val configFileName = Some(s"${System.getenv("PWD")}/test/input/beamville/beam_50.conf")

  lazy val beamConfig = {
    ConfigModule.ConfigFileName = configFileName

    ConfigModule.beamConfig.copy(
      beam = ConfigModule.beamConfig.beam.copy(
        outputs = ConfigModule.beamConfig.beam.outputs.copy(
          eventsFileOutputFormats = "xml,csv"
        )
      )
    )
  }
  val exc = Try(runBeamWithConfig(beamConfig, ConfigModule.matSimConfig))
  val xmlFile: File = getRouteFile(beamConfig.beam.outputs.outputDirectory , "xml")
  val csvFile: File = getRouteFile(beamConfig.beam.outputs.outputDirectory , "csv")
  lazy val route_input = beamConfig.beam.sharedInputs

  val xmlEventsReader: ReadEvents = new ReadEventsXml
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

  "Events file contains all bus routes" should behave like containsAllBusRoutes(busTripsFile, xmlFile, xmlEventsReader)

  "Events file contains all train routes" should behave like containsAllTrainRoutes(trainTripsFile, xmlFile, xmlEventsReader)

  "Events file contains exactly the same bus trips entries" should behave like containsExactlyBusRoutes(busTripsFile, xmlFile, xmlEventsReader)

  "Events file contains exactly the same train trips entries" should behave like containsExactlyTrainRoutes(busTripsFile, xmlFile, xmlEventsReader)

  "Events file contains same pathTraversal defined at stop times file for bus input file" should behave like containsSameBusEntriesPathTraversal(busStopTimesFile,xmlFile,xmlEventsReader)

  "Events file contains same pathTraversal defined at stop times file for train input file" should behave like containsSameTrainEntriesPathTraversal(trainStopTimesFile,xmlFile,xmlEventsReader)
}
//
//class EventsFileCorrectnessSpec extends WordSpecLike with Matchers with RunBeam with BeforeAndAfterAll with EventsFileHandlingCommon{
//
//  lazy val beamConfig = ConfigModule.beamConfig
//
//  lazy val exc = Try(rumBeamWithConfigFile(Some(s"${System.getenv("PWD")}/test/input/beamville/beam_50.conf")))
//  lazy val file: File = getRouteFile(ConfigModule.beamConfig.beam.outputs.outputDirectory , ConfigModule.beamConfig.beam.outputs.eventsFileOutputFormats)
//
//  lazy val route_input = ConfigModule.beamConfig.beam.sharedInputs
//
//  lazy val mode_choice = ConfigModule.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass
//
//  lazy val eventsReader: ReadEvents = getEventsReader(ConfigModule.beamConfig)
//
//  override def beforeAll(): Unit = {
//    exc
//    file
//    route_input
//    eventsReader
//    mode_choice
//  }
//
//  "Run Beam" must {
//    "Start without errors" in {
//      exc.isSuccess shouldBe true
//    }
//
//    "Create file events.xml in output directory" in {
//      file.exists() shouldBe true
//    }
//
//    "Events  file  is correct" in {
//      val fileContents = eventsReader.getLinesFrom(file)
//      (fileContents.contains("<events version") && fileContents.contains("</events>")) shouldBe true
//    }
//
//    "Events file contains all bus routes" in {
//      val route = s"$route_input/r5/bus/trips.txt"
//      val listTrips = getListIDsWithTag(new File(route), "route_id", 2).sorted
//
//      val listValueTagEventFile = eventsReader.getListTagsFrom(new File(file.getPath),"person=\"TransitDriverAgent-bus.gtfs","vehicle")
//
//      listTrips.size shouldBe(listValueTagEventFile.size)
//
//
//    }
//    "Events file contains all train routes" in {
//      val route = s"$route_input/r5/train/trips.txt"
//      val listTrips = getListIDsWithTag(new File(route), "route_id", 2).sorted
//
//      val listValueTagEventFile = eventsReader.getListTagsFrom(new File(file.getPath),"person=\"TransitDriverAgent-train.gtfs","vehicle")
//
//      listTrips.size shouldBe(listValueTagEventFile.size)
//    }
//
//    "Events file contains exactly the same bus trips entries" in {
//
//      val route = s"$route_input/r5/bus/trips.txt"
//      val listTrips = getListIDsWithTag(new File(route), "route_id", 2).sorted
//
//      val listValueTagEventFile = eventsReader.getListTagsFrom(new File(file.getPath),"person=\"TransitDriverAgent-bus.gtfs","vehicle")
//      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
//
//      listTrips shouldBe(listTripsEventFile)
//
//    }
//
//    "Events file contains exactly the same train trips entries" in {
//
//      val route = s"$route_input/r5/train/trips.txt"
//      val listTrips = getListIDsWithTag(new File(route), "route_id", 2).sorted
//
//      val listValueTagEventFile = eventsReader.getListTagsFrom(new File(file.getPath),"person=\"TransitDriverAgent-train.gtfs","vehicle")
//      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
//
//      listTrips shouldBe(listTripsEventFile)
//
//    }
//    "Events file contains same pathTraversal defined at stop times file for train input file" in {
//      val route = s"$route_input/r5/train/stop_times.txt"
//      val listTrips = getListIDsWithTag(new File(route), "trip_id", 0).sorted
//
//      val grouped = listTrips.groupBy(identity)
//      val groupedWithCount = grouped.map{case (k, v) => (k, v.size)}
//
//      val listValueTagEventFile = eventsReader.getListTagsFrom(new File(file.getPath),"type='PathTraversal' vehicle_id='train.gtfs:","vehicle_id")
//      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
//      val groupedXml = listTripsEventFile.groupBy(identity)
//      val groupedXmlWithCount = groupedXml.map{case (k,v) => (k, v.size)}
//
//      groupedWithCount should contain theSameElementsAs(groupedXmlWithCount)
//
//    }
//
//    "Events file contains same pathTraversal defined at stop times file for bus input file" in {
//      val route = s"$route_input/r5/bus/stop_times.txt"
//      val listTrips = getListIDsWithTag(new File(route), "trip_id", 0).sorted
//      val grouped = listTrips.groupBy(identity)
//      val groupedWithCount = grouped.map{case (k, v) => (k, v.size)}
//
//      val listValueTagEventFile = eventsReader.getListTagsFrom(new File(file.getPath),"type='PathTraversal' vehicle_id='bus.gtfs:","vehicle_id")
//      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
//      val groupedXml = listTripsEventFile.groupBy(identity)
//      val groupedXmlWithCount = groupedXml.map{case (k,v) => (k, v.size)}
//
//      groupedWithCount should contain theSameElementsAs(groupedXmlWithCount)
//    }
//
//  }
//}
