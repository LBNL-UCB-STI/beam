package beam.calibration.api

trait ObjectiveFunction {
  def evaluateFromRun(outputFileLoc: String): Double
}

abstract class FileBasedObjectiveFunction(benchmarkFileDataLoc: String) extends ObjectiveFunction
