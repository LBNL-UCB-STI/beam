package beam.sim

import beam.api.{BeamCustomizationAPI, DefaultAPIImplementation}
import beam.utils.{DebugLib, FileUtils, MathUtils}
import ch.qos.logback.classic.util.ContextInitializer
import org.matsim.core.controler.AbstractModule

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
    // this try-catch is needed in case an exception is thrown and the application hangs with some threads locked
    // e.g. we've noticed, that this method LoggingEventsManager#handleBlocking() is likely to have some code
    // that hangs a process and the application cannot be killed, see https://github.com/LBNL-UCB-STI/beam/issues/3524
    try {
      runBeamUsing(args, None)
    } catch {
      case e: Exception =>
        val threadDumpFileName = "thread_dump_from_RunBeam.txt.gz"
        println(s"Exception occurred: ${e.toString}")
        logger.error("Exception occurred:", e)
        FileUtils.writeToFile(threadDumpFileName, DebugLib.currentThreadsDump().asScala.iterator)
        logger.info("Thread dump has been saved to the file {}", threadDumpFileName)
        System.exit(2)
    }
    logger.info("Exiting BEAM")
    System.exit(0)
  }

  def configureDefaultAPI: AbstractModule = {
    val defaultAPIImplementation: BeamCustomizationAPI = new DefaultAPIImplementation()
    val abstractModule = new AbstractModule() {
      override def install(): Unit = {
        bind(classOf[BeamCustomizationAPI]).toInstance(defaultAPIImplementation)
      }
    }
    abstractModule
  }
}
