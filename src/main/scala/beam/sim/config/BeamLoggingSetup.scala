package beam.sim.config

import akka.event.Logging
import akka.event.Logging.LogLevel
import beam.sim.config.BeamConfig.Beam.Outputs
import ch.qos.logback.classic
import ch.qos.logback.classic.LoggerContext
import org.apache.log4j.{ConsoleAppender, FileAppender, Logger, PatternLayout}
import org.apache.log4j
import org.slf4j.LoggerFactory



object BeamLoggingSetup{
  val slf4jDependencies = Vector[String]("com.conveyal")
  val log4jDependencies = Vector[String]("org.matsim")

  def configureLogs(config: BeamConfig) = {
    /*
     * SLF4J
     */
    val root = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val beamLogger = root.getLogger("beam")
    beamLogger.setLevel(classic.Level.toLevel(config.beam.outputs.logging.beam.logLevel,classic.Level.ALL))

    slf4jDependencies.foreach{dependecy =>
      val theLogger = root.getLogger(dependecy)
      theLogger.setLevel(classic.Level.toLevel(config.beam.outputs.logging.dependencies.logLevel,classic.Level.OFF))
    }

    /*
     * LOG4J
     */
    val PATTERN = new PatternLayout("%d{dd MMM yyyy HH:mm:ss} %5p %c{1} - %m%n")
    val console: ConsoleAppender = new ConsoleAppender(PATTERN)
    val rootLogger = Logger.getRootLogger
    rootLogger.addAppender(console)
    rootLogger.setLevel(log4j.Level.ALL)

    val theLogger = Logger.getLogger("beam")
    theLogger.setLevel(slf4JToLog4j(config.beam.outputs.logging.beam.logLevel))
    log4jDependencies.foreach { dependency =>
      val theLogger = Logger.getLogger(dependency)
      theLogger.setLevel(slf4JToLog4j(config.beam.outputs.logging.dependencies.logLevel))
    }
    val fileAppender: FileAppender = new FileAppender(PATTERN, s"${ConfigModule.matSimConfig.controler().getOutputDirectory}/console.log")
    rootLogger.addAppender(fileAppender)

  }

  // We convert a level in the slf4j heirarchy: ALL < TRACE < DEBUG < INFO < WARN < ERROR < OFF
  // To the akka heirarchy: DEBUG < INFO < WARN < ERROR < OFF
  def slf4jLogLevelToAkka(logging: Outputs.Logging): LogLevel = {
    val slfLevel: classic.Level = classic.Level.toLevel(logging.beam.logLevel,classic.Level.ALL)
    slfLevel match {
      case classic.Level.ALL | classic.Level.TRACE | classic.Level.DEBUG =>
        Logging.DebugLevel
      case classic.Level.INFO =>
        Logging.InfoLevel
      case classic.Level.WARN =>
        Logging.WarningLevel
      case classic.Level.ERROR =>
        Logging.ErrorLevel
      case classic.Level.OFF =>
        Logging.levelFor("off").get
    }

  }
  // We convert a level in the slf4j heirarchy: ALL < DEBUG < INFO < WARN < ERROR < OFF
  // To a level in the log4j heirarchy: ALL < TRACE < DEBUG < INFO < WARN < ERROR < FATAL < OFF
  def slf4JToLog4j(theLevel: String): log4j.Level = {
    log4j.Level.toLevel(classic.Level.toLevel(theLevel,classic.Level.ERROR).levelStr)
  }
}