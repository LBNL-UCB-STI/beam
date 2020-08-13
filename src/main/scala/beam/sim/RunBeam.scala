package beam.sim

import beam.utils.MathUtils
import ch.qos.logback.classic.util.ContextInitializer

object RunBeam extends BeamHelper {
  val logbackConfigFile: Option[String] = Option(System.getProperty(ContextInitializer.CONFIG_FILE_PROPERTY))
  if (logbackConfigFile.isEmpty)
    System.setProperty(ContextInitializer.CONFIG_FILE_PROPERTY, "logback.xml")

  def main(args: Array[String]): Unit = {

    print(beamAsciiArt)
    print(s"Heap size: ${MathUtils.formatBytes(Runtime.getRuntime.totalMemory())}")
    print(s"Heap max memory: ${MathUtils.formatBytes(Runtime.getRuntime.maxMemory())}")
    print(s"Heap free memory: ${MathUtils.formatBytes(Runtime.getRuntime.freeMemory())}")

    runBeamUsing(args)
    logger.info("Exiting BEAM")
    System.exit(0)
  }

}
