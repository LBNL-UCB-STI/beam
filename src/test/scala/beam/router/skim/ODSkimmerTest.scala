package beam.router.skim

import beam.sim.{BeamScenario, BeamServices}
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigValueFactory
import com.typesafe.scalalogging.StrictLogging
import org.matsim.core.controler.MatsimServices
import org.matsim.core.controler.events.IterationStartsEvent
import org.mockito.Mockito._
import org.scalatest.FunSuite
import org.scalatestplus.mockito.MockitoSugar

class ODSkimmerTest extends FunSuite with MockitoSugar with StrictLogging {

  val odConstructor: BeamServices => ODSkimmer =
    services => new ODSkimmer(services.matsimServices, services.beamScenario, services.beamConfig)

  val basePath = s"${System.getenv("PWD")}/test/test-resources/beam/od-skims"

  test("Read OD skims from single file with warm start mode") {
    val inputFilePath = s"$basePath/od_for_test.csv.gz"
    val skimmer: ODSkimmer = ODSkimmerTest.createSkimmer(inputFilePath, odConstructor)

    val origData = new CsvSkimReader(inputFilePath, ODSkimmer.fromCsv, logger).readAggregatedSkims
    assert(skimmer.readOnlySkim.aggregatedSkim == origData)
  }

  test("Read OD skims from multi files in the directory with warm start mode") {
    val skimsFilePath = s"$basePath/multi-part-od-skims"
    val skimmer: ODSkimmer = ODSkimmerTest.createSkimmer(skimsFilePath, odConstructor)

    val origData = new CsvSkimReader(
      s"$basePath/od_for_test.csv.gz",
      ODSkimmer.fromCsv,
      logger
    ).readAggregatedSkims
    assert(skimmer.readOnlySkim.aggregatedSkim == origData)
  }
}

object ODSkimmerTest extends MockitoSugar {
  private[skim] def createSkimmer[S <: AbstractSkimmer](inputFilePath: String, constructor: BeamServices => S): S = {
    val beamConfig = BeamConfig(
      testConfig("test/input/beamville/beam.conf")
        .withValue("beam.warmStart.enabled", ConfigValueFactory.fromAnyRef(true))
        .withValue("beam.warmStart.skimsFilePath", ConfigValueFactory.fromAnyRef(inputFilePath))
        .resolve()
    )
    val services = mock[BeamServices]
    when(services.beamConfig).thenReturn(beamConfig)
    when(services.beamScenario).thenReturn(mock[BeamScenario])
    when(services.matsimServices).thenReturn(mock[MatsimServices])

    val event = mock[IterationStartsEvent]
    when(event.getIteration).thenReturn(0)

    val skimmer = constructor(services)
    skimmer.notifyIterationStarts(event)
    skimmer
  }
}
