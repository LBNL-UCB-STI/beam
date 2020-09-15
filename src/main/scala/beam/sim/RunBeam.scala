package beam.sim

import ch.qos.logback.classic.util.ContextInitializer

object RunBeam extends BeamHelper {
  sys.props.getOrElseUpdate(ContextInitializer.CONFIG_FILE_PROPERTY, "logback.xml")

  def main(args: Array[String]): Unit = {

    print(beamAsciiArt)

    runBeamUsing(args)
    logger.info("Exiting BEAM")
    System.exit(0)
  }

}
