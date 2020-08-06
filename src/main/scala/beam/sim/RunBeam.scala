package beam.sim

import ch.qos.logback.classic.util.ContextInitializer

object RunBeam extends BeamHelper {
  val logbackConfigFile: Option[String] = Option(System.getProperty(ContextInitializer.CONFIG_FILE_PROPERTY))
  if (logbackConfigFile.isEmpty)
    System.setProperty(ContextInitializer.CONFIG_FILE_PROPERTY, "logback.xml")

  def main(args: Array[String]): Unit = {

    print(beamAsciiArt)

    runBeamUsing(args)
    logger.info("Exiting BEAM")
    System.exit(0)
  }

}
