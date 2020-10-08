package beam.sim

import ch.qos.logback.classic.util.ContextInitializer

object RunBeamVersion2 extends BeamHelper {
  val logbackConfigFile: Option[String] = Option(System.getProperty(ContextInitializer.CONFIG_FILE_PROPERTY))
  if (logbackConfigFile.isEmpty)
    System.setProperty(ContextInitializer.CONFIG_FILE_PROPERTY, "logback.xml")

  def main(args: Array[String]): Unit = {

    print(beamAsciiArt)
    val runner = buildRunner(args)
    //    runner.addListener(myListener)
    runner.run()
    logger.info("Exiting BEAM")
    System.exit(0)
  }

}
