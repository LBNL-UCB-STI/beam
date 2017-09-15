package beam.sim.config

import ch.qos.logback.classic.LoggerContext
import org.apache.log4j.{ConsoleAppender, Level, Logger, PatternLayout}
import org.slf4j.LoggerFactory
abstract  class MATSIMandR5LoggerController{
  val console: ConsoleAppender
}
object  MATSIMandR5LoggerController {

def muteLog(list: List[String],r5Packages: List[String]): Unit = {
    val PATTERN = new PatternLayout("%d{dd MMM yyyy HH:mm:ss} %5p %c{1} - %m%n")
    //Create appender for INFO log
    val console: ConsoleAppender = new ConsoleAppender
    console.setLayout(PATTERN)
    console.setThreshold(Level.ALL)
    val rootLogger = Logger.getRootLogger
    rootLogger.addAppender(console)
    rootLogger.setLevel(Level.OFF)
    r5Packages.foreach { packageName =>
    val root = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val r5Logger = root.getLogger(packageName)

    r5Logger.setLevel(ch.qos.logback.classic.Level.OFF)
    //Iterate over the logger option
    list.foreach { i =>
      i match {
        case "INFO" =>
          //configure the appender for INFO
          // configures the root logger
          rootLogger.addAppender(console)
          rootLogger.setLevel(Level.INFO)
          r5Logger.setLevel(ch.qos.logback.classic.Level.INFO)


        case "TRACE" =>
          //configure the appender for TRACE
          // configures the root logger
          rootLogger.addAppender(console)
          rootLogger.setLevel(Level.TRACE)
          r5Logger.setLevel(ch.qos.logback.classic.Level.TRACE)


        case "WARN" =>
          //configure the appender for WARN
          // configures the root logger
          rootLogger.addAppender(console)
          rootLogger.setLevel(Level.WARN)
          r5Logger.setLevel(ch.qos.logback.classic.Level.WARN)


        case "ERROR" =>
          //configure the appender for ERROR
          // configures the root logger
          rootLogger.addAppender(console)
          rootLogger.setLevel(Level.ERROR)
          r5Logger.setLevel(ch.qos.logback.classic.Level.ERROR)


        case "DEBUG" =>
          //configure the appender for DEBUG
          // configures the root logger
          rootLogger.addAppender(console)
          rootLogger.setLevel(Level.DEBUG)
          r5Logger.setLevel(ch.qos.logback.classic.Level.DEBUG)


        case _ => "Invalid entry" // the default, catch-all
      }
    }
  }
  }

}
