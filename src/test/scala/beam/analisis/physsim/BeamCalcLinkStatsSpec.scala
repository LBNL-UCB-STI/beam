package beam.analisis.physsim

import java.io.{BufferedInputStream, File, FileInputStream}
import java.util.zip.GZIPInputStream

import beam.utils.BeamCalcLinkStats
import beam.utils.TestConfigUtils.testOutputDir
import org.matsim.analysis.VolumesAnalyzer
import org.matsim.core.config.ConfigUtils
import org.matsim.core.controler.{Controler, OutputDirectoryHierarchy}
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.core.network.io.MatsimNetworkReader
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.core.trafficmonitoring.TravelTimeCalculator
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.xml.XML

class BeamCalcLinkStatsSpec extends WordSpecLike with Matchers with BeforeAndAfterAll {

  private val BASE_PATH = new File("").getAbsolutePath
  private val OUTPUT_DIR_PATH = BASE_PATH + "/" + testOutputDir + "linkstats-test"
  private val EVENTS_FILE_PATH = BASE_PATH + "/test/input/beamville/test-data/linkStatsTest.events.xml"
  private val NETWORK_FILE_PATH = BASE_PATH + "/test/input/beamville/physsim-network.xml"

  private var beamCalcLinkStats: BeamCalcLinkStats = _

  private val TFHOURS = 25
  private val TYPESTATS = 3

  private var fileCsvPath: String = ""

  override def beforeAll(): Unit = {
    val _config = ConfigUtils.createConfig()
    val overwriteExistingFiles =
      OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles
    val outputDirectoryHierarchy =
      new OutputDirectoryHierarchy(OUTPUT_DIR_PATH, overwriteExistingFiles)

    //Read network
    val sc = ScenarioUtils.createScenario(_config)
    val network = sc.getNetwork
    val nwr = new MatsimNetworkReader(network)
    nwr.readFile(NETWORK_FILE_PATH)

    //Start traveltime calculator
    val ttccg = _config.travelTimeCalculator()
    val travelTimeCalculator = new TravelTimeCalculator(network, ttccg)

    //Start eventsmanager
    val events = EventsUtils.createEventsManager()
    events.addHandler(travelTimeCalculator)

    beamCalcLinkStats = new BeamCalcLinkStats(network)
    beamCalcLinkStats.reset()
    val volumes = new VolumesAnalyzer(3600, 24 * 3600 - 1, network)
    events.addHandler(volumes)

    val reader = new MatsimEventsReader(events)
    reader.readFile(EVENTS_FILE_PATH)

    fileCsvPath = outputDirectoryHierarchy.getIterationFilename(0, Controler.FILENAME_LINKSTATS)
    new File(fileCsvPath).getParentFile.mkdirs()

    beamCalcLinkStats.addData(volumes, travelTimeCalculator.getLinkTravelTimes)
    beamCalcLinkStats.writeFile(fileCsvPath)
  }

  "BeamCalcLinksStats" must {

    "Output file contain all links * 25 Hours * 3 StatType" in {
      val expetedResult = countLinksFromFileXML(NETWORK_FILE_PATH) * TFHOURS * TYPESTATS
      val actualResult = gzToBufferedSource(fileCsvPath).getLines().size
      expetedResult shouldBe (actualResult - 1)
    }

    "Each link contains 75 records" in {
      val expetedResult = TFHOURS * TYPESTATS
      val map = mapGroupRecordForLinks(0, fileCsvPath)
      map.foreach {
        case (_, link) =>
          link.size shouldBe expetedResult
      }
    }
  }

  private def gzToBufferedSource(path: String) = {
    Source.fromInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(path))))
  }

  private def countLinksFromFileXML(pathFile: String) = {
    (XML.loadFile(pathFile) \\ "network" \ "links" \ "_").length
  }

  private def mapGroupRecordForLinks(i: Int, pathFile: String) = {
    val bufferedSource = gzToBufferedSource(pathFile)
    val buffer = ArrayBuffer[String]()
    val lines = bufferedSource.getLines()

    for (line <- lines.drop(1)) {
      val cols = line.split(",").map(_.trim)
      buffer.append(cols(i))
    }
    buffer.toList.groupBy(identity)
  }
}
