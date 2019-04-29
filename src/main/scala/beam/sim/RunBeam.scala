package beam.sim

import ch.qos.logback.classic.util.ContextInitializer

object RunBeam extends BeamHelper {
  val isLogbackConfigSet = Option(System.getProperty(ContextInitializer.CONFIG_FILE_PROPERTY)).isDefined
  if (!isLogbackConfigSet)
    System.setProperty(ContextInitializer.CONFIG_FILE_PROPERTY, "logback.xml")

  def main(args: Array[String]): Unit = {

    print(beamAsciiArt)

    runBeamUsing(args)
    logger.info("Exiting BEAM")
    System.exit(0)
  }

}
