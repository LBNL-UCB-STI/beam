package beam.integration
import beam.sim.{BeamHelper, BeamRunner}
import beam.sim.config.BeamConfig
import beam.utils.EventReader._
import com.typesafe.config.{Config, ConfigValueFactory}

class StartWithCustomConfig(val config: Config) extends IntegrationSpecCommon with BeamHelper {

  private lazy val runner: BeamRunner = runBeamWithConfig(
    config.withValue("matsim.modules.controler.lastIteration", ConfigValueFactory.fromAnyRef(0))
  ).run()
  lazy val matsimConfig = runner.matsimConfig

  lazy val groupedCount: Map[String, Int] =
    fromXmlFile(
      getEventsFilePath(matsimConfig, "events", BeamConfig(config).beam.outputs.events.fileOutputFormats).getPath
    ).filter(_.getEventType == "ModeChoice")
      .groupBy(_.getAttributes.get("mode"))
      .map { case (k, v) => (k, v.size) }

}
