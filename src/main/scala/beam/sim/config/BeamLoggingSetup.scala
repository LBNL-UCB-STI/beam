package beam.sim.config

import javax.inject.Inject

import akka.event.Logging
import akka.event.Logging.LogLevel
import org.apache.log4j
import org.apache.log4j._
import org.matsim.core.config.Config

// We convert a level in the slf4j heirarchy: ALL < DEBUG < INFO < WARN < ERROR < OFF
// To a level in the log4j heirarchy: ALL < TRACE < DEBUG < INFO < WARN < ERROR < FATAL < OFF

object BeamLoggingSetup {

  @Inject //FIXME
  var matSimConfig: Config = _

  val dependencies = Vector[String]("org.matsim","com.conveyal")

  def configureLogs(config: BeamConfig) = {
    val PATTERN = new PatternLayout("%d{dd MMM yyyy HH:mm:ss} %5p %c{1} - %m%n")
    val fileAppender: FileAppender = new FileAppender(PATTERN, s"${matSimConfig.controler().getOutputDirectory}/console.log")
    val rootLogger = Logger.getRootLogger
    rootLogger.addAppender(fileAppender)
    rootLogger.setLevel(log4j.Level.OFF)

    val theLogger = Logger.getLogger("beam")
    theLogger.setLevel(Level.toLevel(config.beam.outputs.logging.beam.logLevel,Level.ERROR))
    dependencies.foreach { dependency =>
      val theLogger = Logger.getLogger(dependency)
      theLogger.setLevel(Level.toLevel(config.beam.outputs.logging.dependencies.logLevel, log4j.Level.ERROR))
    }
  }

  // We convert a level in the slf4j heirarchy: ALL < TRACE < DEBUG < INFO < WARN < ERROR < OFF
  // To the akka heirarchy: DEBUG < INFO < WARN < ERROR < OFF
  def log4jLogLevelToAkka(theLevel: String): LogLevel = {
    val log4jLevel = Level.toLevel(theLevel, Level.ERROR)
    log4jLevel match {
      case Level.ALL | log4j.Level.TRACE | Level.DEBUG =>
        Logging.DebugLevel
      case Level.INFO =>
        Logging.InfoLevel
      case Level.WARN =>
        Logging.WarningLevel
      case Level.ERROR | Level.FATAL =>
        Logging.ErrorLevel
      case Level.OFF =>
        Logging.levelFor("off").get
    }
  }
}