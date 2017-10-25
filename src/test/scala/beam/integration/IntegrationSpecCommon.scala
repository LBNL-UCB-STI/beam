package beam.integration

import java.io.File

import beam.sim.RunBeam
import beam.sim.config.{BeamConfig, ConfigModule}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.core.events.handler.BasicEventHandler

import scala.io.Source
import scala.util.Try

import scala.collection.JavaConverters._

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

  //  def getListTagsFromXml(file: File, stringContain: String, tagContain: String): List[String] = {
  //    var listResult = List[String]()
  //    for (line <- Source.fromFile(file.getPath).getLines) {
  //      if (line.contains(stringContain)) {
  //        val temp = scala.xml.XML.loadString(line)
  //        val value = temp.attributes(tagContain).toString
  //        listResult = value:: listResult
  //
  //      }
  //    }
  //
  //    return  listResult
  //  }

  def getRouteFile(route_output: String, extension: String): File = {
    val route = s"$route_output/${getListOfSubDirectories(new File(route_output))}/ITERS/it.0/0.events.$extension"
    new File(route)
  }

  //  def getEventsReader(beamConfig: BeamConfig): ReadEvents = {
  //    beamConfig.beam.outputs.eventsFileOutputFormats match{
  //      case "xml" => new ReadEventsXml
  //      case "csv" => ???
  //      case "xml.gz" => new ReadEventsXMlGz
  //      case "csv.gz" => ???
  //      case _ => throw new RuntimeException("Unsupported format")
  //    }
  //  }

}

trait ReadEvents{
  def getListTagsFromFile(file: File, mkeyValue: Option[(String, String)] = None,
                          tagToReturn: String,
                          eventType: Option[String] = None): Seq[String]

  def getListTagsFrom(filePath: String, mkeyValue: Option[(String, String)] = None,
                      tagToReturn: String,
                      eventType: Option[String] = None): Seq[String]

  def getLinesFrom(file: File): String
}


class ReadEventsBeam extends ReadEvents{
  val basicEventHandler = new BasicEventHandler{
    var events: Seq[Event] = Seq()
    def handleEvent(event: Event): Unit = {
      events = events :+ event
    }
    def reset(iteration: Int): Unit = {
    }
  }

  def getListTagsFromFile(file: File, mkeyValue: Option[(String, String)] = None,
                          tagToReturn: String,
                          eventType: Option[String] = None): Seq[String] = {
    getListTagsFrom(file.getAbsolutePath, mkeyValue, tagToReturn, eventType)
  }

  def getListTagsFrom(filePath: String, mkeyValue: Option[(String, String)] = None,
                      tagToReturn: String,
                      eventType: Option[String] = None): Seq[String] = {
    val eventsMan = EventsUtils.createEventsManager()
    eventsMan.addHandler(basicEventHandler)
    val reader = new MatsimEventsReader(eventsMan)
    reader.readFile(filePath)
    val events = basicEventHandler.events
    val filteredEvents = events.filter{ event =>
      val attributes = event.getAttributes.asScala
      eventType.map(_.equals(event.getEventType)).getOrElse(true) &&
        mkeyValue.map{case (key, value) => attributes.get(key).filter(_.contains(value)).isDefined}.getOrElse(true)

    }
    filteredEvents
      .map(_.getAttributes.asScala.get(tagToReturn))
      .filter(_.isDefined)
      .map(_.get)

  }

  def getLinesFrom(file: File): String = {
    Source.fromFile(file.getPath).getLines.mkString
  }
}

//class ReadEventsXml extends ReadEvents {
//
//  val basicEventHandler = new BasicEventHandler{
//    def handleEvent(event: Event): Unit = {
//      println(s"----------Event--------: $event")
//      println(s"${event.getEventType}")
//      println(s"${event.getAttributes}")
//    }
//
//    def reset(iteration: Int): Unit = {
//    }
//  }
//
//  def getLinesFrom(file: File): String = {
//    Source.fromFile(file.getPath).getLines.mkString
//  }
//
//  def getListTagsFrom(file: File, stringContain: String, tagContain: String): scala.List[String] = {
//
//    val events = EventsUtils.createEventsManager()
//    events.addHandler(basicEventHandler)
//    val reader = new MatsimEventsReader(events)
//    reader.readFile(file.getAbsolutePath)
//
//
//    getListTagsFromLines(Source.fromFile(file.getPath).getLines.toList, stringContain, tagContain)
//  }
//
//  def getListTagsFromLines(file_lines: List[String], stringContain: String, tagContain: String): scala.List[String] = {
//    var listResult = List[String]()
//    for (line <- file_lines) {
//      if (line.contains(stringContain)) {
//        val temp = scala.xml.XML.loadString(line)
//        val value = temp.attributes(tagContain).toString
//        listResult = value:: listResult
//      }
//    }
//    return  listResult
//  }
//}

//class ReadEventsXMlGz extends ReadEventsXml {
//  def extractGzFile(file: File): scala.List[String] = {
//    val fin = new FileInputStream(new java.io.File(file.getPath))
//    val gzis = new GZIPInputStream(fin)
//    val reader =new BufferedReader(new InputStreamReader(gzis, "UTF-8"))
//
//    var lines = new ListBuffer[String]
//
//    while(reader.ready()){
//      lines += reader.readLine()
//    }
//    return  lines.toList
//
//  }
//  override def getListTagsFrom(file: File, stringContain: String, tagContain: String): scala.List[String] = {
//    getListTagsFromLines(extractGzFile(file), stringContain, tagContain)
//  }
//
//  override def getLinesFrom(file: File): String = {
//    extractGzFile(file).mkString
//  }
//}

trait IntegrationSpecCommon {

  def isOrdered[A](s: Seq[A])(cf: (A, A) => Boolean): Boolean = {
    val z1 = s.drop(1)
    val z2 = s.dropRight(1)
    val zip = z2 zip z1

    zip.forall{case (a, b) => cf(a, b)}
  }

  def customBeam(configFileName: Some[String],
                 modeChoice: Option[String] = None,
                 numDriversAsFractionOfPopulation: Option[Double] = None,
                 defaultCostPerMile: Option[Double] = None,
                 defaultCostPerMinute: Option[Double] = None,
                 transitCapacity: Option[Double] = None,
                 transitPrice: Option[Double] = None,
                 tollPrice: Option[Double] = None,
                 rideHailPrice: Option[Double] = None,
                 eventsFileOutputFormats: Option[String] = None) = {
    ConfigModule.ConfigFileName = configFileName

    ConfigModule.beamConfig.copy(
      beam = ConfigModule.beamConfig.beam.copy(
        agentsim = ConfigModule.beamConfig.beam.agentsim.copy(
          agents = ConfigModule.beamConfig.beam.agentsim.agents.copy(
            modalBehaviors = ConfigModule.beamConfig.beam.agentsim.agents.modalBehaviors.copy(
              modeChoiceClass = modeChoice.getOrElse(ConfigModule.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass)
            ), rideHailing = ConfigModule.beamConfig.beam.agentsim.agents.rideHailing.copy(
              defaultCostPerMile = defaultCostPerMile.getOrElse(ConfigModule.beamConfig.beam.agentsim.agents.rideHailing.defaultCostPerMile),
              defaultCostPerMinute = defaultCostPerMinute.getOrElse(ConfigModule.beamConfig.beam.agentsim.agents.rideHailing.defaultCostPerMinute),
              numDriversAsFractionOfPopulation = numDriversAsFractionOfPopulation.getOrElse(ConfigModule.beamConfig.beam.agentsim.agents.rideHailing.numDriversAsFractionOfPopulation)
            )
          ), tuning = ConfigModule.beamConfig.beam.agentsim.tuning.copy(
            transitCapacity = transitCapacity.getOrElse(ConfigModule.beamConfig.beam.agentsim.tuning.transitCapacity),
            transitPrice = transitPrice.getOrElse(ConfigModule.beamConfig.beam.agentsim.tuning.transitPrice),
            tollPrice = tollPrice.getOrElse(ConfigModule.beamConfig.beam.agentsim.tuning.tollPrice),
            rideHailPrice = rideHailPrice.getOrElse(ConfigModule.beamConfig.beam.agentsim.tuning.rideHailPrice)
          )
        ), outputs = ConfigModule.beamConfig.beam.outputs.copy(
          eventsFileOutputFormats =  eventsFileOutputFormats.getOrElse("xml"),
          logging = ConfigModule.beamConfig.beam.outputs.logging.copy(
            beam = ConfigModule.beamConfig.beam.outputs.logging.beam.copy(
              logLevel = "OFF"
            ), dependencies = ConfigModule.beamConfig.beam.outputs.logging.dependencies.copy(
              logLevel = "OFF"
            )
          )
        )
      )
    )
  }
}

class StartWithCustomConfig(
                             modeChoice: Option[String] = None,
                             numDriversAsFractionOfPopulation: Option[Double] = None,
                             defaultCostPerMile: Option[Double] = None,
                             defaultCostPerMinute: Option[Double] = None,
                             transitCapacity: Option[Double] = None,
                             transitPrice: Option[Double] = None,
                             tollPrice: Option[Double] = None,
                             rideHailPrice: Option[Double] = None) extends
  EventsFileHandlingCommon with IntegrationSpecCommon with RunBeam {
  lazy val configFileName = Some(s"${System.getenv("PWD")}/test/input/beamville/beam_50.conf")

  val beamConfig = customBeam(configFileName, modeChoice, numDriversAsFractionOfPopulation,
  defaultCostPerMile,defaultCostPerMinute,transitCapacity,transitPrice,tollPrice,rideHailPrice)

  val exec = Try(runBeamWithConfig(beamConfig, ConfigModule.matSimConfig))

  val file: File = getRouteFile(beamConfig.beam.outputs.outputDirectory , beamConfig.beam.outputs.eventsFileOutputFormats)

  val eventsReader: ReadEvents = new ReadEventsBeam

  val listValueTagEventFile = eventsReader.getListTagsFrom(file.getPath, tagToReturn = "mode", eventType = Some("ModeChoice"))

  val groupedCount = listValueTagEventFile
    .groupBy(s => s)
    .map{case (k, v) => (k, v.size)}
}