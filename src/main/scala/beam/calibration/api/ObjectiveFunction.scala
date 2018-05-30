package beam.calibration.api

trait ObjectiveFunction {
  def evaluateFromRun(outputFileLoc: String): Double
}
