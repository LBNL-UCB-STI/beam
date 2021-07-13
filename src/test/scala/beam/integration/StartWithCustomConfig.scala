package beam.integration

import beam.sim.BeamHelper
import beam.sim.config.BeamConfig
import beam.utils.EventReader._
import com.typesafe.config.{Config, ConfigValueFactory}

class StartWithCustomConfig(val config: Config) extends IntegrationSpecCommon with BeamHelper {

  lazy val (matsimConfig, _, _) = runBeamWithConfig(
    config.withValue("matsim.modules.controler.lastIteration", ConfigValueFactory.fromAnyRef(0))
  )

  lazy val groupedCount: Map[String, Int] =
    fromXmlFile(
      getEventsFilePath(matsimConfig, "events", BeamConfig(config).beam.outputs.events.fileOutputFormats).getPath
    ).filter(_.getEventType == "ModeChoice")
      .groupBy(_.getAttributes.get("mode"))
      .map { case (k, v) => (k, v.size) }

}
