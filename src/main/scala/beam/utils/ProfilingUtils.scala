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

  def timed[U](what: String, work: => U, logger: String => Unit): U = {
    val (r, time) = timed(work)
    logger(s"$what executed in $time ms")
    r
  }
}
