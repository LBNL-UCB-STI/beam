package beam.router.skim.urbansim

import beam.router.skim.ActivitySimSkimmer.ExcerptData
import beam.router.skim.urbansim.BackgroundSkimsCreatorApp.toCsvSkimRow
import beam.sim.BeamConfigChangesObservable
import beam.utils.csv.GenericCsvReader
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.Paths

class BackgroundSkimsCreatorAppSpec extends AnyWordSpecLike with Matchers with ScalaFutures {
  implicit val defaultPatience = PatienceConfig(timeout = Span(30, Seconds))
  val outputPath = Paths.get("output.csv")

  val params = InputParameters(
    configPath = Paths.get("test/input/beamville/beam-with-fullActivitySimBackgroundSkims.conf"),
    input = Some(Paths.get("test/test-resources/beam/router/skim/input.csv")),
    output = outputPath,
    ODSkimsPath = Some(Paths.get("test/test-resources/beam/router/skim/ODSkimsBeamville.csv"))
  )

  "BackgroundSkimsCreatorApp" should {

    "run with parameters" in {
      whenReady(BackgroundSkimsCreatorApp.runWithParams(params)) { _ =>
        BeamConfigChangesObservable.clear()
        val csv = GenericCsvReader.readAs[ExcerptData](outputPath.toString, toCsvSkimRow, _ => true)._1.toVector
        csv.size shouldBe 72
        csv.count(_.weightedGeneralizedTime > 0) shouldBe 10
        csv.count(_.weightedGeneralizedTime > 10) shouldBe 10
      }
    }

    "generate all skims if input is not set" in {
      whenReady(BackgroundSkimsCreatorApp.runWithParams(params.copy(input = None))) { _ =>
        BeamConfigChangesObservable.clear()
        val csv = GenericCsvReader.readAs[ExcerptData](outputPath.toString, toCsvSkimRow, _ => true)._1.toVector
        csv.size shouldBe 564
        csv.count(_.weightedGeneralizedTime > 10) shouldBe 65
      }
    }
  }
}
