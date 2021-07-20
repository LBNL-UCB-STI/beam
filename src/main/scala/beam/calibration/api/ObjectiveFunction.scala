package beam.calibration.api

import org.matsim.core.utils.io.IOUtils

import beam.utils.FileUtils._

trait ObjectiveFunction {
  def evaluateFromRun(runDataPath: String): Double
}

object FileBasedObjectiveFunction {

  //TODO: Generalize and move to CSVUtils
  def writeStatsToFile(stats: Map[String, Double], outputFileLoc: String): Unit = {
    using(IOUtils.getBufferedWriter(outputFileLoc))(writer => {
      stats foreach { case (k, v) =>
        writer.write(s"$k,$v")
        writer.flush()
      }
    })

  }
}
