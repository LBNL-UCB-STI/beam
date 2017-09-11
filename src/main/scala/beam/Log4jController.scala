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
    val console : ConsoleAppender = new ConsoleAppender
    console.setLayout(PATTERN)
    console.setThreshold(Level.ALL)
    val rootLogger = Logger.getRootLogger
    rootLogger.addAppender(console)
    rootLogger.setLevel(Level.OFF)
    //Iterate over the logger option
    list.foreach {i =>
      i match {
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
    }
  }
}