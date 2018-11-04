package beam.integration
import beam.integration.ReadEventsBeam.fromFile
import beam.sim.BeamHelper
import beam.sim.config.BeamConfig
import com.typesafe.config.{Config, ConfigValueFactory}

class StartWithCustomConfig(val config: Config)
    extends EventsFileHandlingCommon
    with IntegrationSpecCommon
    with BeamHelper {

  lazy val (matsimConfig, _) = runBeamWithConfig(config.withValue("matsim.modules.controler.lastIteration", ConfigValueFactory.fromAnyRef(0)))

  lazy val groupedCount = fromFile(getEventsFilePath(matsimConfig, BeamConfig(config).beam.outputs.events.fileOutputFormats).getPath)
      .filter(_.getEventType == "ModeChoice")
      .groupBy(_.getAttributes.get("mode"))
      .map { case (k, v) => (k, v.size) }

}
