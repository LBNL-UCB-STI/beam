package beam.router.skim.urbansim

import beam.router.skim.ActivitySimSkimmer.ExcerptData
import beam.router.skim.urbansim.BackgroundSkimsCreatorApp.toCsvSkimRow
import beam.utils.csv.GenericCsvReader
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.Paths

class BackgroundSkimsCreatorAppSpec extends AnyWordSpecLike with Matchers with ScalaFutures {
  implicit val defaultPatience = PatienceConfig(timeout = Span(30, Seconds))

  "BackgroundSkimsCreatorApp" should {
    "run with parameters" in {
      val outputPath = Paths.get("output.csv")
      val params = InputParameters(
        configPath = Paths.get("test/input/beamville/beam-with-fullActivitySimBackgroundSkims.conf"),
        input = Paths.get("test/test-resources/beam/router/skim/input.csv"),
        output = outputPath,
        ODSkimsPath = Some(Paths.get("test/test-resources/beam/router/skim/ODSkimsBeamville.csv"))
      )
      whenReady(BackgroundSkimsCreatorApp.runWithParams(params)) { _ =>
        val csv = GenericCsvReader.readAs[ExcerptData](outputPath.toString, toCsvSkimRow, _ => true)._1.toVector
        csv.size shouldBe 15
        csv.count(_.weightedGeneralizedTime > 0) shouldBe 15
        csv.count(_.weightedGeneralizedTime > 10) shouldBe 2
      }
    }
  }
}
