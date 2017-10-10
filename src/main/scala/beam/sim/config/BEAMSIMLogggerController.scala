package beam.sim.config

import ch.qos.logback.classic.{Level, LoggerContext}
import org.slf4j.LoggerFactory

import scala.collection.immutable.List

abstract  class BEAMSIMLogggerController{
  val root: LoggerFactory
}
object BEAMSIMLogggerController {

  def cutOff(classList: List[String], loggerLevel: String): Unit = {
    val root = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    //get package or class to control it's logging
    classList.foreach{
      className =>

        val rootLogger = root.getLogger(className)
        rootLogger.setLevel(Level.OFF)

        loggerLevel match {
          case "INFO" =>
            rootLogger.setLevel(Level.INFO) //configures the root logger for INFO
          case "TRACE" =>
            rootLogger.setLevel(Level.TRACE) //configures the root logger for TRACE
          case "WARN" =>
            rootLogger.setLevel(Level.WARN) //configures the root logger for WARN
          case "ERROR" =>
            rootLogger.setLevel(Level.ERROR) //configures the root logger for ERROR
          case "DEBUG" =>
            rootLogger.setLevel(Level.DEBUG) //configures the root logger for DEBUG
          case _ => "Invalid entry" // the default, catch-all

        }

    }
  }


}
