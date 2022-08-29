package beam.utils

import beam.analysis.DelayMetricAnalysis
import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.BeamHelper
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.events.Event
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}

import scala.collection.mutable.ArrayBuffer

object EventReplayer extends BeamHelper {

  def main(args: Array[String]): Unit = {
    val path = """C:\temp\0.events.xml"""
    // Initialization
    val (_, config) = prepareConfig(args, isConfigArgRequired = true)
    val beamConfig = BeamConfig(config)
    val networkCoordinator = DefaultNetworkCoordinator(beamConfig)
    networkCoordinator.init()
    val network = networkCoordinator.network

    val networkHelper = new NetworkHelperImpl(network)
    println("Initialized network")

    println("Reading events...")
    val events = readEvents(path)

    val eventHandler: BasicEventHandler =
      new DelayMetricAnalysis(EventsUtils.createEventsManager(), null, networkHelper)

    val maxIter = 1
    val s = System.currentTimeMillis()
    var iter: Int = 1
    while (iter <= maxIter) {
      var i: Int = 0
      while (i < events.length) {
        eventHandler.handleEvent(events(i))
        i += 1
      }
      iter += 1
    }
    val e = System.currentTimeMillis()
    val total = e - s
    val avg = total.toDouble / maxIter

    println(
      s"DelayMetricAnalysis processed ${events.size}. Total time $total ms, average $avg ms, number of iterations $maxIter"
    )
  }

  private def readEvents(path: String): IndexedSeq[Event] = {
    val eventsManager = EventsUtils.createEventsManager()
    var numOfEvents: Int = 0
    val buf = new ArrayBuffer[Event]()
    eventsManager.addHandler(new BasicEventHandler {
      def handleEvent(event: Event): Unit = {
        if (event.getEventType == "PathTraversal") {
          // Do something with need `PathTraversalEvent`
          // buf += new PathTraversalEvent(event.getTime, event.getAttributes)
        }
        buf += event
        numOfEvents += 1
      }
    })
    new MatsimEventsReader(eventsManager).readFile(path)
    println(s"Read $numOfEvents from '$path'")
    buf
  }
}
