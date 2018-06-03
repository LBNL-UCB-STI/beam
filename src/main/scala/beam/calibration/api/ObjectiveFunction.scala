package beam.calibration.api

trait RunEvaluator[A] {
  def evaluateFromRun(runData: A): Double
}

object EvaluationUtil {
  def evaluate[A](data: A)(implicit ev: RunEvaluator[A]): Double = {
    ev.evaluateFromRun(data)
  }
}

trait ObjectiveFunction {
  def evaluateFromRun(outputFileLoc: String): Double
}

abstract class FileBasedObjectiveFunction(benchmarkFileDataLoc: String) extends ObjectiveFunction
