package beam.sim.config

import akka.event.Logging
import akka.event.Logging.LogLevel
import org.apache.log4j
import org.apache.log4j._

// We convert a level in the slf4j heirarchy: ALL < DEBUG < INFO < WARN < ERROR < OFF
// To a level in the log4j heirarchy: ALL < TRACE < DEBUG < INFO < WARN < ERROR < FATAL < OFF

object BeamLoggingSetup {

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