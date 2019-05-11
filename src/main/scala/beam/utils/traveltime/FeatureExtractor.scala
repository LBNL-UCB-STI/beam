package beam.utils.traveltime

import java.io.Writer

import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link

trait FeatureExtractor {
  def fields: Seq[Schema.Field]

  def csvWriteHeader(wrt: Writer): Unit

  def enteredLink(event: Event, link: Link, vehicleId: String, linkVehicleCount: scala.collection.Map[Int, Int])

  def leavedLink(
    event: Event,
    link: Link,
    vehicleId: String,
    linkVehicleCount: scala.collection.Map[Int, Int],
    record: GenericData.Record
  ): Unit
}
