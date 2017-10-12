package beam.integration

import beam.sim.RunBeam
import beam.sim.config.ConfigModule
import org.matsim.core.events.{EventsManagerImpl, EventsReaderXMLv1}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import java.io.{BufferedReader, File, FileInputStream, InputStreamReader}
import java.util.zip.GZIPInputStream

import scala.xml.XML
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.util.Try

/**
  * Created by fdariasm on 29/08/2017
  * 
  */

class ModeChoiceSpec extends WordSpecLike with Matchers with RunBeam with BeforeAndAfterAll{

  class StartWithModeChoice(modeChoice: String) extends EventsFileHandlingCommon{
    lazy val configFileName = Some(s"${System.getenv("PWD")}/test/input/beamville/beam_50.conf")

    val beamConfig = {

      ConfigModule.ConfigFileName = configFileName

      ConfigModule.beamConfig.copy(
        beam = ConfigModule.beamConfig.beam.copy(
          agentsim = ConfigModule.beamConfig.beam.agentsim.copy(
            agents = ConfigModule.beamConfig.beam.agentsim.agents.copy(
              modalBehaviors = ConfigModule.beamConfig.beam.agentsim.agents.modalBehaviors.copy(
                modeChoiceClass = modeChoice
              )
            )
          ), outputs = ConfigModule.beamConfig.beam.outputs.copy(
            eventsFileOutputFormats = "xml"
          )
        )
      )
    }

    val exec = Try(runBeamWithConfig(beamConfig, ConfigModule.matSimConfig))
    val file: File = getRouteFile(beamConfig.beam.outputs.outputDirectory , beamConfig.beam.outputs.eventsFileOutputFormats)
    val eventsReader: ReadEvents = getEventsReader(beamConfig)
  }

  def maxRepetition(listValueTag: List[String]): String = {
    val grouped = listValueTag.groupBy(s => s)
    val groupedCount = grouped.map{case (k, v) => (k, v.size)}
    val (maxK, _) = groupedCount.max

    println(s"-----------GroupedCount $groupedCount")

    maxK
  }

  "Running beam with modeChoiceClass ModeChoiceDriveIfAvailable" must {
    "prefer mode choice car type than other modes" in new StartWithModeChoice("ModeChoiceDriveIfAvailable"){
      val listValueTagEventFile = eventsReader.getListTagsFrom(new File(file.getPath),"type=\"ModeChoice\"","mode")
      val maxK = maxRepetition(listValueTagEventFile)
      maxK shouldBe "car"
    }
  }

  "Running beam with modeChoiceClass ModeChoiceTransitIfAvailable" must {
    "prefer mode choice transit type than other modes" in new StartWithModeChoice("ModeChoiceTransitIfAvailable"){
      val listValueTagEventFile = eventsReader.getListTagsFrom(new File(file.getPath),"type=\"ModeChoice\"","mode")
      val maxK = maxRepetition(listValueTagEventFile)
      maxK shouldBe "transit"
    }
  }

  "Running beam with modeChoiceClass ModeChoiceTransitOnly" must {
    "Generate ModeChoice events file with only transit types" in new StartWithModeChoice("ModeChoiceTransitOnly"){
      val listValueTagEventFile = eventsReader.getListTagsFrom(new File(file.getPath),"type=\"ModeChoice\"","mode")
      listValueTagEventFile.filter(s => s.equals("transit")).size shouldBe listValueTagEventFile.size
    }
  }

  "Running beam with modeChoiceClass ModeChoiceRideHailIfAvailable" must {
    "prefer more mode choice ride hail type than other modes" in new StartWithModeChoice("ModeChoiceRideHailIfAvailable"){
      val listValueTagEventFile = eventsReader.getListTagsFrom(new File(file.getPath),"type=\"ModeChoice\"","mode")

      val maxK = maxRepetition(listValueTagEventFile)
      maxK shouldBe "ride_hailing"
    }
  }

  "Running beam with modeChoiceClass ModeChoiceMultinomialLogit" must {
    "Generate events file with for ModeChoice" in new StartWithModeChoice("ModeChoiceMultinomialLogit"){
      fail("Unpredictable output to evaluate")
    }
  }

  "Running beam with modeChoiceClass ModeChoiceDriveOnly" must {
      "Generate ModeChoice events file with only car types" in new StartWithModeChoice("ModeChoiceDriveOnly"){
      val listValueTagEventFile = eventsReader.getListTagsFrom(new File(file.getPath),"type=\"ModeChoice\"","mode")
      listValueTagEventFile.filter(s => s.equals("car")).size shouldBe listValueTagEventFile.size
      
    }
  }

  "Running beam with modeChoiceClass ModeChoiceRideHailOnly" must {
    "Generate ModeChoice events file with only ride hail types" in new StartWithModeChoice("ModeChoiceRideHailOnly"){
      val listValueTagEventFile = eventsReader.getListTagsFrom(new File(file.getPath),"type=\"ModeChoice\"","mode")
      listValueTagEventFile.filter(_.equals("ride_hailing")).size shouldBe listValueTagEventFile.size
    }
  }


  //Commented out for now as beam is hanging during run
//  "Running beam with modeChoiceClass ModeChoiceUniformRandom" must {
//    "Generate events file with exactly four ride_hailing type for ModeChoice" in new StartWithModeChoice("ModeChoiceUniformRandom"){
//      //val listValueTagEventFile = eventsReader.getListTagsFrom(new File(file.getPath),"type=\"ModeChoice\"","mode")
//      //listValueTagEventFile.filter(s => s.equals("ride_hailing")).size shouldBe(4)
//      fail("Beam doesn't work in this ModeChoice")
//    }
//  }
}
