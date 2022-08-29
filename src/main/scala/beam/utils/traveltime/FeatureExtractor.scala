package beam.utils.traveltime

import java.io.Writer

import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link

trait FeatureExtractor {
  def writeHeader(wrt: Writer): Unit
  def enteredLink(event: Event, link: Link, vehicleId: String, linkVehicleCount: scala.collection.Map[Int, Int])

  def leavedLink(
    wrt: Writer,
    event: Event,
    link: Link,
    vehicleId: String,
    linkVehicleCount: scala.collection.Map[Int, Int]
  )
}
