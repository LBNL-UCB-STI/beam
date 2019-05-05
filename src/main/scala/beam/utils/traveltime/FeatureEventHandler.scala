package beam.utils.traveltime

import java.io.{File, PrintWriter}
import java.util

import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link
import org.matsim.core.events.handler.BasicEventHandler

import scala.collection.mutable

class FeatureEventHandler(
  val links: util.Map[Id[Link], _ <: Link],
  val delimiter: String,
  val outputPath: String,
  val featureExtractor: FeatureExtractor
) extends BasicEventHandler
    with LazyLogging
    with AutoCloseable {
  val linkVehicleCount: mutable.Map[Int, Int] = mutable.Map[Int, Int]()
  val vehicleToEnterTime: mutable.Map[String, Double] = mutable.Map[String, Double]()

  val vehiclesInFrontOfMe: mutable.Map[String, Int] = mutable.Map[String, Int]()

  val writer = new PrintWriter(new File(outputPath))

  writeHeader()

  def writeColumnValue(value: String): Unit = {
    writer.append(value)
    writer.append(delimiter)
  }

  override def close(): Unit = {
    writer.flush()
    writer.close()
  }

  def writeHeader(): Unit = {
    writeColumnValue("linkId")
    writeColumnValue("vehicleId")
    writeColumnValue("travelTime")
    writeColumnValue("enterTime")
    writeColumnValue("leaveTime")
    writeColumnValue("vehOnRoad")
    writeColumnValue("length")

    featureExtractor.writeHeader(writer)

    // Do not use `writeColumnValue`, it adds delimiter, but this is the last column
    writer.append("dummy_column")
    writer.append(System.lineSeparator())
    writer.flush()
  }

  def handleEvent(event: Event): Unit = {
    val attrib = event.getAttributes
    val linkId = Option(attrib.get("link")).map(_.toInt).get
    val link = links.get(Id.create(attrib.get("link"), classOf[Link]))
    event.getEventType match {
      case "entered link" | "vehicle enters traffic" | "wait2link" =>
        val enterTime = event.getTime
        val vehicleId = attrib.get("vehicle")
        vehiclesInFrontOfMe.put(vehicleId, linkVehicleCount.getOrElse(linkId, 0))
        vehicleToEnterTime.put(vehicleId, enterTime)

        featureExtractor.enteredLink(event, link, vehicleId, linkVehicleCount)
        linkVehicleCount.put(linkId, linkVehicleCount.getOrElse(linkId, 0) + 1)

      case "vehicle leaves traffic" =>
        linkVehicleCount.put(linkId, linkVehicleCount.getOrElse(linkId, 0) - 1)

      case "left link" =>
        val vehicleId = attrib.get("vehicle")
        val enterTime = vehicleToEnterTime(vehicleId)
        val leaveTime = event.getTime
        val travelTime = leaveTime - enterTime
        writeColumnValue(linkId.toString)
        writeColumnValue(vehicleId)
        writeColumnValue(travelTime.toString)
        writeColumnValue(enterTime.toString)
        writeColumnValue(leaveTime.toString)

        linkVehicleCount.put(linkId, linkVehicleCount.getOrElse(linkId, 0) - 1)
        val numOfVehicleOnTheRoad = vehiclesInFrontOfMe(vehicleId)
        writeColumnValue(numOfVehicleOnTheRoad.toString)
        writeColumnValue(link.getLength.toString)

        featureExtractor.leavedLink(writer, event, link, vehicleId, linkVehicleCount)

        // Do not use `writeColumnValue`, it adds delimiter, but this is the last column
        writer.append("d")
        writer.append(System.lineSeparator())
      case _ =>
    }
  }
}
