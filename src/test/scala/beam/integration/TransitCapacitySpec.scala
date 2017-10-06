package beam.integration

import java.io.File

import beam.sim.RunBeam
import beam.sim.config.ConfigModule
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.util.Try

/**
  * Created by fdariasm on 29/08/2017
  * 
  */

class TransitCapacitySpec extends WordSpecLike with Matchers with RunBeam with BeforeAndAfterAll{

  class StartWithModeChoiceAndTransitCapacity(modeChoice: String, transitCapacity: Double) extends EventsFileHandlingCommon{
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
            ), tuning = ConfigModule.beamConfig.beam.agentsim.tuning.copy(
              transitCapacity = transitCapacity
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
    val listValueTagEventFile = eventsReader.getListTagsFrom(new File(file.getPath),"type=\"ModeChoice\"","mode")
    val groupedCount = listValueTagEventFile
      .groupBy(s => s)
      .map{case (k, v) => (k, v.size)}
  }

  "Running beam with modeChoiceClass ModeChoiceDriveIfAvailable" must {
    "prefer more mode choice car type than other modes" in{
      val inputTransitCapacity = 0.1 to 1.0 by 0.1
      val modeChoice = inputTransitCapacity.map(tc => new StartWithModeChoiceAndTransitCapacity("ModeChoiceTransitIfAvailable", tc).groupedCount)

      val tc = modeChoice
        .map(_.get("transit"))
        .filter(_.isDefined)
        .map(_.get)

      val z1 = tc.drop(1)
      val z2 = tc.dropRight(1)
      val zip = z2 zip z1
      zip.forall{ case (a, b) => a <= b} shouldBe true
    }
  }


}
