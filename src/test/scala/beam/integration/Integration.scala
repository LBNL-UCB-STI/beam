package beam.integration

import beam.sim.RunBeam
import beam.sim.config.ConfigModule
import org.matsim.core.events.{EventsManagerImpl, EventsReaderXMLv1}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import java.io.File

import scala.collection.JavaConverters._
import scala.io.Source
import scala.util.Try

/**
  * Created by fdariasm on 29/08/2017
  * 
  */
class Integration extends WordSpecLike with Matchers with RunBeam{

  val eventManager = new EventsManagerImpl()
  val eventsReader = new EventsReaderXMLv1(eventManager)



  // assumes that dir is a directory known to exist
  def getListOfSubDirectories(dir: File): List[String] =
    dir.listFiles
      .filter(_.isDirectory)
      .map(_.getName)
      .toList

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





  "Run Beam" must {

    var route_output = ""
    var file: File = null
    var route_input  =""



    "Start without errors" in {


      val exc = Try(rumBeamWithConfigFile(Some(s"${System.getenv("PWD")}/test/input/beamville/beam.conf")))
      route_output = ConfigModule.beamConfig.beam.outputs.outputDirectory
      route_input = ConfigModule.beamConfig.beam.sharedInputs
      exc.isSuccess shouldBe true
    }

    "Create file events.xml in output directory" in {
      val route = s"$route_output/${getListOfSubDirectories(new File(route_output)).sorted.reverse.head}/ITERS/it.0/0.events.xml"
      file = new File(route)
      file.exists() shouldBe true
    }

    "Events  file  is correct" in {
      val fileContents = Source.fromFile(file.getPath).getLines.mkString
      (fileContents.contains("<events version") && fileContents.contains("</events>")) shouldBe true
    }

    "Events file contains all bus routes" in {
      val route = s"$route_input/r5/bus/trips.txt"
      val listTrips = getListIDsWithTag(new File(route), "route_id", 2).sorted

      val listValueTagEventFile = getListTagsFromXml(new File(file.getPath),"person='TransitDriverAgent-bus.gtfs","vehicle")

      listTrips.size shouldBe(listValueTagEventFile.size)


    }
    "Events file contains all trail routes" in {
      val route = s"$route_input/r5/train/trips.txt"
      val listTrips = getListIDsWithTag(new File(route), "route_id", 2).sorted

      val listValueTagEventFile = getListTagsFromXml(new File(file.getPath),"person='TransitDriverAgent-train.gtfs","vehicle")

      listTrips.size shouldBe(listValueTagEventFile.size)

    }


    "Events file contains exactly the same bus trips of entry" in {

      val route = s"$route_input/r5/bus/trips.txt"
      val listTrips = getListIDsWithTag(new File(route), "route_id", 2).sorted

      val listValueTagEventFile = getListTagsFromXml(new File(file.getPath),"person='TransitDriverAgent-bus.gtfs","vehicle")
      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted

      listTrips shouldBe(listTripsEventFile)

    }

    "Events file contains exactly the same train trips of entry" in {

      val route = s"$route_input/r5/train/trips.txt"
      val listTrips = getListIDsWithTag(new File(route), "route_id", 2).sorted

      val listValueTagEventFile = getListTagsFromXml(new File(file.getPath),"person='TransitDriverAgent-train.gtfs","vehicle")
      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted

      listTrips shouldBe(listTripsEventFile)

    }
    "Events file should contain same pathTraversal defined at stop times file for train input file" in {
      val route = s"$route_input/r5/train/stop_times.txt"
      val listTrips = getListIDsWithTag(new File(route), "trip_id", 0).sorted

      val grouped = listTrips.groupBy(identity)
      val groupedWithCount = grouped.map{case (k, v) => (k, v.size)}

      val listValueTagEventFile = getListTagsFromXml(new File(file.getPath),"type='PathTraversal' vehicle_id='train.gtfs:","vehicle_id")
      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
      val groupedXml = listTripsEventFile.groupBy(identity)
      val groupedXmlWithCount = groupedXml.map{case (k,v) => (k, v.size)}

      groupedWithCount should contain theSameElementsAs(groupedXmlWithCount)

    }

    "Events file should contain same pathTraversal defined at stop times file for bus input file" in {

      val route = s"$route_input/r5/bus/stop_times.txt"
      val listTrips = getListIDsWithTag(new File(route), "trip_id", 0).sorted
      val grouped = listTrips.groupBy(identity)
      val groupedWithCount = grouped.map{case (k, v) => (k, v.size)}

      val listValueTagEventFile = getListTagsFromXml(new File(file.getPath),"type='PathTraversal' vehicle_id='bus.gtfs:","vehicle_id")
      val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
      val groupedXml = listTripsEventFile.groupBy(identity)
      val groupedXmlWithCount = groupedXml.map{case (k,v) => (k, v.size)}

      groupedWithCount should contain theSameElementsAs(groupedXmlWithCount)


    }






  }

}
