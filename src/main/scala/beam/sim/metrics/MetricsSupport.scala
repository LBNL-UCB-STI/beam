package beam.sim.metrics

import beam.sim.metrics.Metrics._
import kamon.Kamon
import kamon.metric.instrument.Histogram
import kamon.util.Latency


trait MetricsSupport {

  def countOccurrence(name: String, level: MetricLevel) = if (isRightLevel(level)) Kamon.metrics.counter(name).increment()

  def increment(name: String, level: MetricLevel) = if (isRightLevel(level)) Kamon.metrics.minMaxCounter(name).increment()

  def decrement(name: String, level: MetricLevel) = if (isRightLevel(level)) Kamon.metrics.minMaxCounter(name).decrement()

  def latency[A](name: String, level: MetricLevel)(thunk: => A): A = if (isRightLevel(level)) Latency.measure(Kamon.metrics.histogram(name))(thunk) else thunk

  def record(name: String, level: MetricLevel, nanoTime: Long, tags: Map[String, String] = Map()) = if (isRightLevel(level)) Kamon.metrics.histogram(name, tags).record(nanoTime)

  def latencyIfNonNull[A](name: String, level: MetricLevel)(thunk: => A): A = if (isRightLevel(level)) {
    val resultWithTime = measure(thunk)
    if(resultWithTime._1 != null) {
      record(name, level, resultWithTime._2)
    }
    resultWithTime._1
  } else thunk

  def startMeasuringIteration(itNum: Int) = startMeasuring("iteration", ShortLevel, Map("it-num"->(""+itNum)))

  def stopMeasuringIteration() = stopMeasuring()

  def startMeasuring(name: String, level: MetricLevel): Unit = startMeasuring(name, level, Map.empty)

  def startMeasuring(name: String, level: MetricLevel, tags: Map[String, String]) = if (isRightLevel(level)) Metrics.setCurrentContext(Kamon.tracer.newContext(name, None, tags))

  def stopMeasuring() = if(Metrics.currentContext != null && !Metrics.currentContext.isClosed) Metrics.currentContext.finish()


  def startSegment(name: String, categry: String) = if(Metrics.currentContext != null && !Metrics.currentContext.isClosed && !currentSegments.contains(name+":"+categry))
    currentSegments += (name+":"+categry -> Metrics.currentContext.startSegment(name, categry, "kamon"))

  def endSegment(name: String, categry: String) = currentSegments.remove(name+":"+categry) match {
    case Some(segment) =>
      segment.finish()
    case _ =>
  }

  def measure[A](thunk: ⇒ A): (A, Long) = {
    val start = System.nanoTime()
    (thunk, System.nanoTime() - start)
  }

  def measure[A](histogram: Histogram)(thunk: ⇒ A): A = {
    val start = System.nanoTime()
    try thunk finally {
      val latency = System.nanoTime() - start
      histogram.record(latency)
    }
  }
}

