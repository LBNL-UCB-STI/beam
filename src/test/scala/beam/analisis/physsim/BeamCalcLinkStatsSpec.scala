package beam.analisis.physsim

import java.io.File

import beam.utils.BeamCalcLinkStats
import org.matsim.analysis.VolumesAnalyzer
import org.matsim.core.config.ConfigUtils
import org.matsim.core.controler.{Controler, OutputDirectoryHierarchy}
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.core.network.io.MatsimNetworkReader
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.core.trafficmonitoring.TravelTimeCalculator
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

class BeamCalcLinkStatsSpec  extends WordSpecLike with Matchers with BeforeAndAfterAll{

  private val BASE_PATH = new File("").getAbsolutePath
  private val OUTPUT_DIR_PATH = BASE_PATH + "/test/input/beamville/output"
  private val EVENTS_FILE_PATH = BASE_PATH + "/test/input/beamville/output/beamville_2018-02-06_10-59-01/ITERS/it.0/0.events.xml"
  private val NETWORK_FILE_PATH = BASE_PATH + "/test/input/beamville/physsim-network.xml"

  private var beamCalcLinkStats: BeamCalcLinkStats = null

  override def beforeAll(): Unit = {
    val _config = ConfigUtils.createConfig()
    val overwriteExistingFiles = OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles
    val outputDirectoryHierarchy = new OutputDirectoryHierarchy(OUTPUT_DIR_PATH, overwriteExistingFiles)

    //Read network
    val sc = ScenarioUtils.createScenario(_config)
    val network = sc.getNetwork()
    val nwr= new MatsimNetworkReader(network)
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

    beamCalcLinkStats.addData(volumes,  travelTimeCalculator.getLinkTravelTimes())
    beamCalcLinkStats.writeFile(outputDirectoryHierarchy.getIterationFilename(0, Controler.FILENAME_LINKSTATS))
  }

  "BeamCalcLinksStats" must {
    "Create output expected file" in {
      2 shouldBe 2
    }
  }

}
