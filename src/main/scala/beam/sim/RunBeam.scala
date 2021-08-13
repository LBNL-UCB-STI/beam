package beam.sim

import beam.utils.MathUtils
import beam.api.{BeamCustomizationAPI, DefaultAPIImplementation}
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

    runBeamUsing(args, None)
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
