package beam.utils

object ProfilingUtils {
  def timed[U](work: => U): (U, Long) = {
    val startTime = System.currentTimeMillis()
    val data = work
    val endTime = System.currentTimeMillis()
    (data, endTime - startTime)
  }

  def timeWork[U](work: => U): Long = {
    val startTime = System.currentTimeMillis()
    val data = work
    val endTime = System.currentTimeMillis()
    endTime - startTime
  }
}
