package beam.sim.metrics

import beam.sim.metrics.Metrics._
import kamon.Kamon
import kamon.context.{Context, Key}
import kamon.metric.Histogram

import scala.collection.JavaConverters._

trait MetricsSupport {

  //  def countOccurrence(name: String, level: MetricLevel, tags: Map[String, String] = Map.empty): Unit = if (isRightLevel(level)) Kamon.metrics.counter(name, defaultTags ++ tags).increment()

  def countOccurrence(name: String, times: Long = 1, level: MetricLevel, tags: Map[String, String] = Map.empty): Unit = if (isRightLevel(level)) Kamon.counter(name).increment(times)

  def countOccurrenceJava(name: String, times: Long = 1, level: MetricLevel, tags: java.util.Map[String, String] = new java.util.HashMap()): Unit = countOccurrence(name, times, level, tags.asScala.toMap)

  def increment(name: String, level: MetricLevel) = if (isRightLevel(level)) Kamon.rangeSampler(name).increment()

  def decrement(name: String, level: MetricLevel) = if (isRightLevel(level)) Kamon.rangeSampler(name).decrement()

  def latency[A](name: String, level: MetricLevel)(thunk: => A): A =  {
    if (isRightLevel(level)) {
      measure(Kamon.histogram(name))(thunk)
    }
    else
      thunk
  }

  def latency(name: String, level: MetricLevel, nanoTime: Long): Unit = {
    //if (isRightLevel(level)) Kamon.metrics.histogram(name).record(nanoTime)
  }

  def record(name: String, level: MetricLevel, nanoTime: Long, tags: Map[String, String] = Map.empty) = if (isRightLevel(level)) Kamon.histogram(name).refine(defaultTags ++ tags).record(nanoTime)

  def latencyIfNonNull[A](name: String, level: MetricLevel)(thunk: => A): A = if (isRightLevel(level)) {
    val resultWithTime = measure(thunk)
    if(resultWithTime._1 != null) {
      record(name, level, resultWithTime._2)
    }
    resultWithTime._1
  } else thunk

  def startMeasuringIteration(itNum: Int) = startMeasuring("iteration", ShortLevel)

  def stopMeasuringIteration() = stopMeasuring()

  def startMeasuring(name: String, level: MetricLevel): Unit = startMeasuring(name, level, Map.empty)

  def startMeasuring(name: String, level: MetricLevel, tags: Map[String, String]) = if (isRightLevel(level)) {
    val context = Context(Key.local("main_name", ""), name)
    (defaultTags ++ tags).foreach{
      case (key, value) => context.withKey(Key.local(key, ""), value)
    }
    Metrics.setCurrentContext(context)
  }//Kamon.tracer.newContext(name, None, defaultTags ++ tags))

  def stopMeasuring() = if(Metrics.currentContext != null && !Metrics.currentSpans.isEmpty){//!Metrics.currentContext.isClosed) {
    Metrics.currentSpans.foreach{
      case (_, span) => span.finish
    }
    //Metrics.currentContext.finish()
  }


  def startSpan(name: String, categry: String) =
    if(Metrics.currentContext != null /*&& !Metrics.currentContext.isClosed*/ && !currentSpans.contains(name+":"+categry)) {

      val originalTags = Map(
        "trace" -> name,
        "category" -> categry,
        "library" -> "kamon"
      )
      val spanBuilder = Kamon.tracer.buildSpan(name)
      originalTags.foreach{
        case(key, value) => spanBuilder.withMetricTag(key, value)
      }
      defaultTags.foreach{
        case(key, value) => spanBuilder.withTag(key, value)
      }
      currentSpans += (name+":"+categry -> spanBuilder.start())//Metrics.currentContext.buildSpan(name, categry, "kamon", defaultTags))
    }


  def endSpan(name: String, categry: String) = currentSpans.remove(name+":"+categry) match {
    case Some(span) =>
      span.finish()
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

