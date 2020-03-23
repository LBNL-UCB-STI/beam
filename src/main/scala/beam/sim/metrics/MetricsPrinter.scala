//package beam.sim.metrics
//
//import java.nio.LongBuffer
//
//import akka.actor.{Actor, Props}
//import beam.sim.metrics.MetricsPrinter.{Print, Subscribe}
//import com.typesafe.scalalogging.LazyLogging
//import kamon.Kamon
//import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
//import kamon.metric.instrument.CollectionContext
//import kamon.metric.instrument.Time.{Milliseconds, Nanoseconds}
//import kamon.metric.{Entity, EntitySnapshot}
//
//class MetricsPrinter(val includes: Seq[String], val excludes: Seq[String]) extends Actor with LazyLogging {
//  var iterationNumber = 0
//  var metricStore: Map[Entity, EntitySnapshot] = _
//
//  val collectionContext: CollectionContext {
//    val buffer: LongBuffer
//  } = new CollectionContext {
//    val buffer: LongBuffer = LongBuffer.allocate(100000)
//  }
//
//  import context._
//
//  def receive: Receive = {
//    case Subscribe(category, selection) if Metrics.isMetricsEnable =>
//      Kamon.metrics.subscribe(category, selection, self)
//      become(subscribed)
//    case _ =>
//      logger.debug("Printer not subscribed.")
//  }
//
//  def subscribed: Receive = {
//    case Subscribe(category, selection) if Metrics.isMetricsEnable =>
//      Kamon.metrics.subscribe(category, selection, self)
//
//    case tickSnapshot: TickMetricSnapshot =>
//      if (metricStore == null) {
//        metricStore = tickSnapshot.metrics
//      } else {
//        metricStore = metricStore.map(m => {
//          var snap = m._2
//          val ms = tickSnapshot.metrics.filter(_._1 == m._1)
//          ms.foreach(cm => {
//            snap = snap.merge(cm._2, collectionContext)
//          })
//          (m._1, snap)
//        })
//        //        println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
//        //        metricStore.map(_._2.histogram("histogram").get.numberOfMeasurements).foreach(println)
//        //        println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
//      }
//
//    case Print(ins, exs) =>
//      if (metricStore != null) {
//        val counters = metricStore.filterKeys(c => c.category == "counter" && ins.contains(c.name))
//        val histograms =
//          metricStore.filterKeys(h => h.category == "histogram" && ins.contains(h.name))
//
//        var text = ""
//
//        if (ins == null) {
//          histograms.foreach { case (e, s) => text += toHistogramString(e, s) }
//          counters.foreach { case (e, s)   => text += toCounterString(e, s) }
//        } else {
//          histograms.filterKeys(in => ins.contains(in.name)).foreach {
//            case (e, s) => text += toHistogramString(e, s)
//          }
//          counters.filterKeys(in => ins.contains(in.name)).foreach {
//            case (e, s) => text += toCounterString(e, s)
//          }
//        }
//        if (text != null && !text.isEmpty) {
//          logger.info(s"""
//             |=======================================================================================
//             | Performance Benchmarks (iteration no: $iterationNumber)
//             $text
//             |
//             |=======================================================================================
//             """.stripMargin)
//        }
//        metricStore = null
//        iterationNumber = iterationNumber + 1
//      }
//  }
//
//  private def toHistogramString(e: Entity, s: EntitySnapshot): String = {
//    val hs = s.histogram("histogram").get.scale(Nanoseconds, Milliseconds)
//    val num = hs.numberOfMeasurements
//    if (num <= 0) ""
//    else {
//
//      val ttime = hs.sum
//      val p99_9 = hs.percentile(99.9)
//      val max = hs.max
//
//      s"""
//         | ${e.name} -> count: $num; average time: ${ttime / num} [ms]; max time: $max [ms]; total time: ${ttime / 1000} [s]""".stripMargin
//    }
//  }
//
//  private def toCounterString(e: Entity, s: EntitySnapshot): String = {
//    val cs = s.counter("counter").get
//
//    s"""
//       | ${e.name} -> count: ${cs.count}""".stripMargin
//  }
//}
//
//object MetricsPrinter {
//  case class Subscribe(category: String, selection: String)
//  case class Print(includes: Seq[String], excludes: Seq[String])
//  def props() = Props(new MetricsPrinter(null, null))
//}
