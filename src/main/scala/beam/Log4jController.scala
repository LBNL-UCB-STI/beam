package beam;
import org.apache.log4j.ConsoleAppender
import org.apache.log4j.Level
import org.apache.log4j.PatternLayout
import org.apache.log4j.Logger
abstract  class Log4jController{
  val console: ConsoleAppender
}
object Log4jController {

  def muteLog(list: List[String]): Unit = {
    val PATTERN = new PatternLayout("%d{dd MMM yyyy HH:mm:ss} %5p %c{1} - %m%n")
    //Create appender for INFO log
    val consoleINFO : ConsoleAppender = new ConsoleAppender
    consoleINFO.setLayout(PATTERN)
    consoleINFO.setThreshold(Level.INFO)
    consoleINFO.activateOptions
    val rootLogger = Logger.getRootLogger
    rootLogger.addAppender(consoleINFO)
    rootLogger.setLevel(Level.OFF)
    //Create appender for TRACE log
    val consoleTRACE : ConsoleAppender = new ConsoleAppender
    consoleTRACE.setLayout(PATTERN)
    consoleTRACE.setThreshold(Level.TRACE)
    consoleTRACE.activateOptions
    rootLogger.addAppender(consoleTRACE)
    rootLogger.setLevel(Level.OFF)
    //Create appender for WARN log
    val consoleWARN : ConsoleAppender = new ConsoleAppender
    consoleWARN.setLayout(PATTERN)
    consoleWARN.setThreshold(Level.DEBUG)
    consoleWARN.activateOptions
    rootLogger.addAppender(consoleWARN)
    rootLogger.setLevel(Level.OFF)
    //Create appender for ERROR log
    val consoleERROR : ConsoleAppender = new ConsoleAppender
    consoleERROR.setLayout(PATTERN)
    consoleERROR.setThreshold(Level.ERROR)
    consoleERROR.activateOptions
    rootLogger.addAppender(consoleERROR)
    rootLogger.setLevel(Level.OFF)
    //Create appender for DEBUG log
    val consoleDEBUG : ConsoleAppender = new ConsoleAppender
    consoleDEBUG.setLayout(PATTERN)
    consoleDEBUG.setThreshold(Level.DEBUG)
    consoleDEBUG.activateOptions
    rootLogger.addAppender(consoleDEBUG)
    rootLogger.setLevel(Level.OFF)
    //Iterate over the logger option
   list.foreach {i =>
     i match
      {
        case "INFO" =>
          //configure the appender for INFO
          // configures the root logger
          rootLogger.addAppender(consoleINFO)
          rootLogger.setLevel(Level.INFO)

        case "TRACE" =>
          //configure the appender for TRACE
          // configures the root logger
          rootLogger.addAppender(consoleTRACE)
          rootLogger.setLevel(Level.TRACE)

        case "WARN" =>
          //configure the appender for WARN
          // configures the root logger
          rootLogger.addAppender(consoleWARN)
          rootLogger.setLevel(Level.WARN)

        case "ERROR" =>
          //configure the appender for ERROR
          // configures the root logger
          rootLogger.addAppender(consoleERROR)
          rootLogger.setLevel(Level.ERROR)

        case "DEBUG" =>
          //configure the appender for DEBUG
          // configures the root logger
          rootLogger.addAppender(consoleDEBUG)
          rootLogger.setLevel(Level.DEBUG)

        case _ => "Invalid entry" // the default, catch-all
      }
    }
  }
}