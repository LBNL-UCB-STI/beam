package beam.router.skim

import beam.router.skim.Skims.SkimType
import beam.router.skim.core.{AbstractSkimmer, ODSkimmer}
import beam.sim.{BeamScenario, BeamServices}
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigValueFactory
import com.typesafe.scalalogging.StrictLogging
import org.matsim.core.controler.MatsimServices
import org.matsim.core.controler.events.IterationStartsEvent
import org.mockito.Mockito._
import org.scalatest.funsuite.AnyFunSuite

class ODSkimmerTest extends AnyFunSuite with StrictLogging {

  val odConstructor: BeamServices => ODSkimmer =
    services => new ODSkimmer(services.matsimServices, services.beamScenario, services.beamConfig)

  val basePath = s"${System.getenv("PWD")}/test/test-resources/beam/od-skims"

  test("Read OD skims from single file with warm start mode") {
    val inputFilePath = s"$basePath/od_for_test.csv.gz"
    val skimmer: ODSkimmer = ODSkimmerTest.createSkimmer(SkimType.OD_SKIMMER, inputFilePath, odConstructor)

    val origData = new CsvSkimReader(inputFilePath, ODSkimmer.fromCsv, logger).readAggregatedSkims
    assert(skimmer.readOnlySkim.aggregatedFromPastSkims == origData)
  }

  test("Read OD skims from multi files in the directory with warm start mode") {
    val skimsFilePath = s"$basePath/multi-part-od-skims"
    val skimmer: ODSkimmer = ODSkimmerTest.createSkimmer(SkimType.OD_SKIMMER, skimsFilePath, odConstructor)

    val origData = new CsvSkimReader(
      s"$basePath/od_for_test.csv.gz",
      ODSkimmer.fromCsv,
      logger
    ).readAggregatedSkims
    assert(skimmer.readOnlySkim.aggregatedFromPastSkims == origData)
  }
}

object ODSkimmerTest {
  private[skim] def createSkimmer[S <: AbstractSkimmer](
    skimType: SkimType.Value,
    inputFilePath: String,
    constructor: BeamServices => S
  ): S = {
    import scala.collection.JavaConverters._
    val beamConfig = BeamConfig(
      testConfig("test/input/beamville/beam.conf")
        .withValue("beam.warmStart.type", ConfigValueFactory.fromAnyRef("full"))
        .withValue(
          "beam.warmStart.skimsFilePaths",
          ConfigValueFactory.fromIterable(
            List(
              ConfigValueFactory.fromAnyRef(
                Map("skimType" -> skimType.toString, "skimsFilePath" -> inputFilePath).asJava
              )
            ).asJava
          )
        )
        .resolve()
    )
    val services = mock(classOf[BeamServices])
    when(services.beamConfig).thenReturn(beamConfig)
    when(services.beamScenario).thenReturn(mock(classOf[BeamScenario]))
    when(services.matsimServices).thenReturn(mock(classOf[MatsimServices]))

    val event = mock(classOf[IterationStartsEvent])
    when(event.getIteration).thenReturn(0)

    val skimmer = constructor(services)
    skimmer.notifyIterationStarts(event)
    skimmer
  }
}
