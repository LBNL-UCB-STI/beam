package beam.analysis

import java.io.File

import com.zaxxer.nuprocess.{NuAbstractProcessHandler, NuProcess, NuProcessBuilder}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import beam.utils.logging.ExponentialLazyLogging

object AnalysisProcessor extends ExponentialLazyLogging {

  def firePythonScriptAsync(scriptPath: String, args: String*): NuProcess = {
    val processBuilder = new NuProcessBuilder((Array("py", scriptPath) ++ args): _*)
    val processHandler = new ProcessHandler(source = "Python Script: " + scriptPath)
    processBuilder.setProcessListener(processHandler)
    logger.info(s"Running python script: $scriptPath with args $args")
    processBuilder.start()
  }
}

class ProcessHandler(var nuProcess: NuProcess = null, source: String)
    extends NuAbstractProcessHandler
    with ExponentialLazyLogging {
  override def onStart(nuProcess: NuProcess) = {
    this.nuProcess = nuProcess
  }

  override def onStdout(buffer: ByteBuffer, closed: Boolean) = {
    val output = StandardCharsets.UTF_8.decode(buffer).toString
    logger.info(s"Process Handler Stdout for $source: $output")
    println(s"Process Handler Stdout for $source: $output")
  }

  override def onStderr(buffer: ByteBuffer, closed: Boolean) {
    val output = StandardCharsets.UTF_8.decode(buffer).toString
    logger.error(s"Process Handler Stderr for $source: $output")
    println(s"Process Handler Stdout for $source: $output")
  }
}
