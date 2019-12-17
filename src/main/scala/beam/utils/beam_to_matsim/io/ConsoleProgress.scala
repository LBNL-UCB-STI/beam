package beam.utils.beam_to_matsim.io

class ConsoleProgress(message: String, maxSteps: Int, percentageToWrite: Int = 10) {

  private val onePercent = maxSteps / 100
  private val stepToWrite = onePercent * percentageToWrite
  private var nextStepToWrite = stepToWrite
  private var currentStep = 0

  private val startTime = System.currentTimeMillis()

  print(s"progress report for every $percentageToWrite% (for every $stepToWrite)")

  def step(): Unit = {
    currentStep += 1
    if (currentStep >= nextStepToWrite) {
      write()
      nextStepToWrite += stepToWrite
    }
  }

  def print(message: String): Unit = Console.println(s"\t$message")

  def write(): Unit = {
    val percentage = currentStep / onePercent
    if (percentage < 100) {
      print(s"$percentage%  $message")
    }
  }

  def finish(): Unit = {
    val execTime = (System.currentTimeMillis() - startTime) / 1000.0
    if (execTime > 1.0) {
      print(s"100% $message ($execTime sec total)")
    } else {
      print(s"100% $message")
    }
  }
}
