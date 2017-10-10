package beam.sim.config

import ch.qos.logback.classic.LoggerContext
import org.apache.log4j.{ConsoleAppender, Level, Logger, PatternLayout}
import org.slf4j.LoggerFactory
abstract  class MATSIMandR5LoggerController{
  val console: ConsoleAppender
}
object  MATSIMandR5LoggerController {

def muteLog(loggerLevel: String,r5Packages: List[String]): Unit = {
    val PATTERN = new PatternLayout("%d{dd MMM yyyy HH:mm:ss} %5p %c{1} - %m%n")
    //Create appender for INFO log
    val console: ConsoleAppender = new ConsoleAppender
    console.setLayout(PATTERN)
    console.setThreshold(Level.ALL)

  //For MATSIM Logger Control
    val rootLogger = Logger.getRootLogger
    rootLogger.addAppender(console)
    rootLogger.setLevel(Level.OFF)
    //Iterate over the logger option

  loggerLevel match {
        case "INFO" =>
          //configure the appender for INFO
          // configures the root logger
          rootLogger.addAppender(console)
          rootLogger.setLevel(Level.INFO)

        case "TRACE" =>
          //configure the appender for TRACE
          // configures the root logger
          rootLogger.addAppender(console)
          rootLogger.setLevel(Level.TRACE)

        case "WARN" =>
          //configure the appender for WARN
          // configures the root logger
          rootLogger.addAppender(console)
          rootLogger.setLevel(Level.WARN)

        case "ERROR" =>
          //configure the appender for ERROR
          // configures the root logger
          rootLogger.addAppender(console)
          rootLogger.setLevel(Level.ERROR)

        case "DEBUG" =>
          //configure the appender for DEBUG
          // configures the root logger
          rootLogger.addAppender(console)
          rootLogger.setLevel(Level.DEBUG)

        case _ => "Invalid entry" // the default, catch-all
      }

  // For r5 logger control
    r5Packages.foreach { packageName =>
    val root = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val r5Logger = root.getLogger(packageName)

    r5Logger.setLevel(ch.qos.logback.classic.Level.OFF)

      loggerLevel match {
        case "INFO" =>
          // configures the r5logger for INFO
          r5Logger.setLevel(ch.qos.logback.classic.Level.INFO)


        case "TRACE" =>
          // configures the r5logger for TRACE
          r5Logger.setLevel(ch.qos.logback.classic.Level.TRACE)


        case "WARN" =>
          // configures the r5logger for WARN
          r5Logger.setLevel(ch.qos.logback.classic.Level.WARN)


        case "ERROR" =>
          // configures the r5logger for ERROR
          r5Logger.setLevel(ch.qos.logback.classic.Level.ERROR)


        case "DEBUG" =>
          // configures the r5logger for DEBUG
          r5Logger.setLevel(ch.qos.logback.classic.Level.DEBUG)


        case _ => "Invalid entry" // the default, catch-all
      }
    }
  }

}
