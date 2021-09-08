package beam.analysis

import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.TimeUnit
import scala.sys.process._

import beam.utils.logging.ExponentialLazyLogging

case class PythonProcess(processOption: Option[Process]) {

  def isRunning: Boolean = processOption match {
    case Some(process) => process.isAlive
    case None          => false
  }

  def waitFor(timeLength: Int, timeUnit: TimeUnit): AnyVal = {
    processOption match {
      case Some(process) =>
        try {
          Await.result(
            Future(blocking(process.exitValue)),
            duration.Duration(timeLength, timeUnit)
          )
        } catch {
          case _: TimeoutException => process.destroy
        }
      case None =>
    }
  }
}

object AnalysisProcessor extends ExponentialLazyLogging {

  def firePythonScriptAsync(scriptPath: String, args: String*): PythonProcess = {
    firePythonAsync("python3", scriptPath, args: _*)
  }

  def firePythonAsync(processName: String, scriptPath: String, args: String*): PythonProcess = {
    try {
      val source = "Python Script: " + scriptPath
      val processLogger = ProcessLogger(
        output => {
          logger.info(s"Process Handler Stdout for $source: $output")
          println(s"Process Handler Stdout for $source: $output")
        },
        output => {
          logger.error(s"Process Handler Stderr for $source: $output")
          println(s"Process Handler Stdout for $source: $output")
        }
      )
      logger.info(s"Running python script: $scriptPath with args $args")
      val str = (Seq(processName, scriptPath) ++ args).mkString(" ")
      PythonProcess(Some(str.run(processLogger)))
    } catch {
      case ex: Throwable =>
        logger.error(s"Error running python script $scriptPath $args: $ex")
        PythonProcess(None)
    }
  }
}
