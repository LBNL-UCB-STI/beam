package beam.integration

import beam.sim.RunBeam
import beam.sim.config.ConfigModule
import org.matsim.core.events.{EventsManagerImpl, EventsReaderXMLv1}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import java.io.File

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


  "Run Beam" must {

    var route_output = ""
    var file: File = null



    "Start without errors" in {


      val exc = Try(rumBeamWithConfigFile(Some(s"${System.getenv("PWD")}/test/input/beamville/beam.conf")))
      route_output = ConfigModule.beamConfig.beam.outputs.outputDirectory
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

  }

}
