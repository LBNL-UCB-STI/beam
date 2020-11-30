package beam.sim

import beam.utils.MathUtils
import ch.qos.logback.classic.util.ContextInitializer

import scala.collection.JavaConverters._

object RunBeam extends BeamHelper {
  val logbackConfigFile: Option[String] = Option(System.getProperty(ContextInitializer.CONFIG_FILE_PROPERTY))
  if (logbackConfigFile.isEmpty)
    System.setProperty(ContextInitializer.CONFIG_FILE_PROPERTY, "logback.xml")

  def main(args: Array[String]): Unit = {
    println(beamAsciiArt)

    println(s"Received ${args.length} arguments: ${args.toList}")
    println(s"Java version: ${System.getProperty("java.version")}")
    println(
      "JVM args: " + java.lang.management.ManagementFactory.getRuntimeMXBean.getInputArguments.asScala.toList.toString()
    )
    println(s"Heap size: ${MathUtils.formatBytes(Runtime.getRuntime.totalMemory())}")
    println(s"Heap max memory: ${MathUtils.formatBytes(Runtime.getRuntime.maxMemory())}")
    println(s"Heap free memory: ${MathUtils.formatBytes(Runtime.getRuntime.freeMemory())}")

    runBeamUsing(args)
    logger.info("Exiting BEAM")
    System.exit(0)
  }

}
