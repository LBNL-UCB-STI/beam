package beam.router.skim

import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigValueFactory
import com.typesafe.scalalogging.StrictLogging
import org.matsim.core.controler.events.IterationStartsEvent
import org.mockito.Mockito._
import org.scalatest.FunSuite
import org.scalatestplus.mockito.MockitoSugar

class ODSkimmerTest extends FunSuite with MockitoSugar with StrictLogging {

  test("Read OD skims from single file with warm start mode") {
    val inputFilePath = getClass.getResource("/files/od_for_test.csv.gz").getFile
    val beamConfig = BeamConfig(
      testConfig("test/input/beamville/beam.conf")
        .withValue("beam.warmStart.enabled", ConfigValueFactory.fromAnyRef(true))
        .withValue("beam.warmStart.skimsFilePath", ConfigValueFactory.fromAnyRef(inputFilePath))
        .resolve()
    )
    val services = mock[BeamServices]
    when(services.beamConfig).thenReturn(beamConfig)

    val event = mock[IterationStartsEvent]
    when(event.getIteration).thenReturn(0)

    val skimmer = new ODSkimmer(services, beamConfig.beam.router.skim)
    skimmer.notifyIterationStarts(event)

    val origData = new CsvSkimReader(inputFilePath, ODSkimmer.fromCsv, logger).readAggregatedSkims
    assert(skimmer.readOnlySkim.aggregatedSkim == origData)
  }

  test("Read OD skims from multi files in the directory with warm start mode") {
    val skimsFilePath = getClass.getResource("/files/multi-part-od-skims").getFile
    val beamConfig = BeamConfig(
      testConfig("test/input/beamville/beam.conf")
        .withValue("beam.warmStart.enabled", ConfigValueFactory.fromAnyRef(true))
        .withValue("beam.warmStart.skimsFilePath", ConfigValueFactory.fromAnyRef(skimsFilePath))
        .resolve()
    )
    val services = mock[BeamServices]
    when(services.beamConfig).thenReturn(beamConfig)

    val event = mock[IterationStartsEvent]
    when(event.getIteration).thenReturn(0)

    val skimmer = new ODSkimmer(services, beamConfig.beam.router.skim)
    skimmer.notifyIterationStarts(event)

    val origData = new CsvSkimReader(
      getClass.getResource("/files/od_for_test.csv.gz").getFile,
      ODSkimmer.fromCsv,
      logger
    ).readAggregatedSkims
    assert(skimmer.readOnlySkim.aggregatedSkim == origData)
  }
}
