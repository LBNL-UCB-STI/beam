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

class TollPriceSpec extends WordSpecLike with Matchers with RunBeam with BeforeAndAfterAll with IntegrationSpecCommon {

  class StartWithModeChoiceAndTollPrice(modeChoice: String, tollPrice: Double) extends EventsFileHandlingCommon{
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
              tollPrice = tollPrice
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

  "Running beam with modeChoice ModeChoiceTransitIfAvailable and increasing tollCapacity value" must {
    "create less entries for mode choice car as value increases" in{
      val inputTollPrice = Seq(0.1, 1.0)
      val modeChoice = inputTollPrice.map(tc => new StartWithModeChoiceAndTollPrice("ModeChoiceMultinomialLogit", tc).groupedCount)

      val tc = modeChoice
        .map(_.get("car"))
        .filter(_.isDefined)
        .map(_.get)

      val z1 = tc.drop(1)
      val z2 = tc.dropRight(1)
      val zip = z2 zip z1

      println("Transit")
      println(tc)
      println(z1)
      println(z2)
      println(zip)

      isOrdered(tc)((a, b) => a >= b) shouldBe true
    }
  }


}
