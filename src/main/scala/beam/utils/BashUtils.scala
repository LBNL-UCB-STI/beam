package beam.utils

import java.io.{BufferedReader, InputStreamReader}

object BashUtils {

  /**
    * Method returns the git commit hash or HEAD if git not present
    * git rev-parse --abbrev-ref HEAD
    *
    * @return returns the git commit hash or HEAD if git not present
    */
  def getCommitHash: String = {
    val resp = readCommandResponse("git rev-parse HEAD")
    if (resp != null) resp
    else "HEAD" //for the env where git is not present

  }

  /**
    * Method returns the git branch or master if git not present
    *
    * @return returns the current git branch
    */
  def getBranch: String = {
    val resp = readCommandResponse("git rev-parse --abbrev-ref HEAD")
    if (resp != null) resp
    else "master"
  }

  private def readCommandResponse(command: String) = {
    val runtime = Runtime.getRuntime
    try {
      val process = runtime.exec(command)
      val reader = new BufferedReader(new InputStreamReader(process.getInputStream))
      try reader.readLine
      finally if (reader != null) reader.close()
    } catch {
      case _: Exception =>
        null //for the env where command is not recognized
    }
  }
}
