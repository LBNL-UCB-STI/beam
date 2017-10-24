package beam.integration

import java.io.{BufferedReader, File, FileInputStream, InputStreamReader}
import java.nio.file.{Path, Paths}
import java.util.zip.GZIPInputStream

import beam.integration.Integration._
import beam.sim.RunBeam
import beam.sim.config.ConfigModule
import org.scalatest.{BeforeAndAfterAll, Ignore, Matchers, WordSpecLike}

import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.util.Try

/**
  * Created by fdariasm on 29/08/2017
  * 
  */

object Integration {

  trait ReadEvents {
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
          listResult = value :: listResult

        }
      }
      return listResult
    }


  }

  class ReadEventsXMlGz extends ReadEventsXml {


    def extractGzFile(file: File): scala.List[String] = {
      val fin = new FileInputStream(new java.io.File(file.getPath))
      val gzis = new GZIPInputStream(fin)
      val reader = new BufferedReader(new InputStreamReader(gzis, "UTF-8"))

      var lines = new ListBuffer[String]

      while (reader.ready()) {
        lines += reader.readLine()
      }
      return lines.toList

    }

    override def getListTagsFrom(file: File, stringContain: String, tagContain: String): scala.List[String] = {
      getListTagsFromLines(extractGzFile(file), stringContain, tagContain)
    }

    override def getLinesFrom(file: File): String = {
      extractGzFile(file).mkString
    }
  }

}


class Integration extends WordSpecLike with Matchers with RunBeam with BeforeAndAfterAll{


  //Obtains name of latest created folder
  //Assumes that dir is a directory known to exist
  def getListOfSubDirectories(dir: File): String = {
    val simName = ConfigModule.beamConfig.beam.agentsim.simulationName
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

  lazy val exc = Try(rumBeamWithConfigFile(Some(s"${System.getenv("PWD")}/test/input/beamville/beam.conf")))
  lazy val eventXmlFile: File = new File(ConfigModule.beamConfig.beam.outputs.outputDirectory).toPath.resolve("ITERS/it.0/0.events.xml").toFile

  lazy val route_input = ConfigModule.beamConfig.beam.sharedInputs

  lazy val eventsReader: ReadEvents = new ReadEventsXml

  override def beforeAll(): Unit = {
    exc
    eventXmlFile
    route_input
    eventsReader
    mode_choice
  }

  "Run Beam" must {
    "Execute without errors" in {
      exc.isSuccess shouldBe true
    }

    "Create file events.xml in output directory" ignore {
      eventXmlFile.exists() shouldBe true
    }

    "Events  file  is correct" ignore {
      val fileContents = eventsReader.getLinesFrom(eventXmlFile)
      (fileContents.contains("<events version") && fileContents.contains("</events>")) shouldBe true
    }

    "Events file contains all bus routes" ignore {
      val route = s"$route_input/r5/bus/trips.txt"
      val listTrips = getListIDsWithTag(new File(route), "route_id", 2).sorted

      val listValueTagEventFile = eventsReader.getListTagsFrom(new File(eventXmlFile.getPath),"person=\"TransitDriverAgent-bus.gtfs","vehicle")

      listTrips.size shouldBe(listValueTagEventFile.size)


    }
    "Events file contains all train routes" ignore {
      val route = s"$route_input/r5/train/trips.txt"
      val listTrips = getListIDsWithTag(new File(route), "route_id", 2).sorted

      val listValueTagEventFile = eventsReader.getListTagsFrom(new File(eventXmlFile.getPath),"person=\"TransitDriverAgent-train.gtfs","vehicle")

      listTrips.size shouldBe(listValueTagEventFile.size)
    }

    "Events file contains exactly the same bus trips entries" ignore {

      val route = s"$route_input/r5/bus/trips.txt"
      val listTrips = getListIDsWithTag(new File(route), "route_id", 2).sorted

      val listValueTagEventFile = eventsReader.getListTagsFrom(new File(eventXmlFile.getPath),"person=\"TransitDriverAgent-bus.gtfs","vehicle")
      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted

      listTrips shouldBe(listTripsEventFile)

    }

    "Events file contains exactly the same train trips entries" ignore {

      val route = s"$route_input/r5/train/trips.txt"
      val listTrips = getListIDsWithTag(new File(route), "route_id", 2).sorted

      val listValueTagEventFile = eventsReader.getListTagsFrom(new File(eventXmlFile.getPath),"person=\"TransitDriverAgent-train.gtfs","vehicle")
      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted

      listTrips shouldBe(listTripsEventFile)

    }
    "Events file contain same pathTraversal defined at stop times file for train input file" ignore {
      val route = s"$route_input/r5/train/stop_times.txt"
      val listTrips = getListIDsWithTag(new File(route), "trip_id", 0).sorted

      val grouped = listTrips.groupBy(identity)
      val groupedWithCount = grouped.map{case (k, v) => (k, v.size)}

      val listValueTagEventFile = eventsReader.getListTagsFrom(new File(eventXmlFile.getPath),"type='PathTraversal' vehicle_id='train.gtfs:","vehicle_id")
      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
      val groupedXml = listTripsEventFile.groupBy(identity)
      val groupedXmlWithCount = groupedXml.map{case (k,v) => (k, v.size)}

      groupedWithCount should contain theSameElementsAs(groupedXmlWithCount)

    }

    "Events file contain same pathTraversal defined at stop times file for bus input file" ignore {
      val route = s"$route_input/r5/bus/stop_times.txt"
      val listTrips = getListIDsWithTag(new File(route), "trip_id", 0).sorted
      val grouped = listTrips.groupBy(identity)
      val groupedWithCount = grouped.map{case (k, v) => (k, v.size)}

      val listValueTagEventFile = eventsReader.getListTagsFrom(new File(eventXmlFile.getPath),"type='PathTraversal' vehicle_id='bus.gtfs:","vehicle_id")
      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
      val groupedXml = listTripsEventFile.groupBy(identity)
      val groupedXmlWithCount = groupedXml.map{case (k,v) => (k, v.size)}

      groupedWithCount should contain theSameElementsAs(groupedXmlWithCount)
    }

    "Events file contains exactly one transit type for ModeChoice and 3 ride_hailing type entries for ModeChoice when modeChoice is ModeChoiceTransitIfAvailable in input file" in {

      if (mode_choice.equals("ModeChoiceTransitIfAvailable")){
        val listValueTagEventFile = eventsReader.getListTagsFrom(new File(file.getPath),"type=\"ModeChoice\"","mode")
        listValueTagEventFile.filter(s => s.equals("transit")).size shouldBe(1)
        listValueTagEventFile.filter(s => s.equals("ride_hailing")).size shouldBe(3)
      }
      else
        succeed

    }

    "Events file contains exactly two car type for ModeChoice and two ride_hailing type entries for ModeChoice when modeChoice is ModeChoiceDriveIfAvailable in input file" in {

      if (mode_choice.equals("ModeChoiceDriveIfAvailable")){
        val listValueTagEventFile = eventsReader.getListTagsFrom(new File(file.getPath),"type=\"ModeChoice\"","mode")
        listValueTagEventFile.filter(s => s.equals("car")).size shouldBe(2)
        listValueTagEventFile.filter(s => s.equals("ride_hailing")).size shouldBe(2)
      }
      else
        succeed

    }

    "Events file contains exactly 4 ride_hailing type entries for ModeChoice when modeChoice is ModeChoiceRideHailIfAvailable in input file" in {

      if (mode_choice.equals("ModeChoiceRideHailIfAvailable")){
        val listValueTagEventFile = eventsReader.getListTagsFrom(new File(file.getPath),"type=\"ModeChoice\"","mode")
        listValueTagEventFile.filter(s => s.equals("ride_hailing")).size shouldBe(4)
      }
      else
        succeed

    }

    "Events file contain exactly one transit type for ModeChoice when modeChoice is ModeChoiceTransitOnly in input file" in {
      if (mode_choice.equals("ModeChoiceTransitOnly")){
        val listValueTagEventFile = eventsReader.getListTagsFrom(new File(file.getPath),"type=\"ModeChoice\"","mode")
        listValueTagEventFile.filter(s => s.equals("transit")).size shouldBe(1)
      }
      else
        succeed

    }



  }
}
