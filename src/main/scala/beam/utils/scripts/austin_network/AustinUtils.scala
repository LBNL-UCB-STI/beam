package beam.utils.scripts.austin_network

import beam.sim.common.GeoUtils
import beam.utils.scripts.austin_network.AustinUtils.getGeoUtils
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.network.Link
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.NetworkReaderMatsimV2

import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.collection.JavaConverters._

//TODO: push some of this out to more general library
object AustinUtils {

  val getGeoUtils = new GeoUtils {
    override def localCRS: String = "epsg:26910"
  }

  def getFileLines(filePath: String): Vector[String] = {
    val source = Source.fromFile(filePath)
    var lines = source.getLines.toVector
    source.close
    lines
  }

  def getPhysSimNetwork(filePath: String) = {
    val network = NetworkUtils.createNetwork
    val reader = new NetworkReaderMatsimV2(network)
    reader.readFile(filePath)
    network
  }









}

case class DataVector(linkId: DataId, startCoord: Coord, endCoord: Coord, isWGS: Boolean) {

  def produceSpeedDataPointFromSpeedVector(splitSizeInMeters: Double): ArrayBuffer[DataPoint] = {
    val distance = if (isWGS) getGeoUtils.distLatLon2Meters(startCoord, endCoord) else getGeoUtils.distUTMInMeters(startCoord, endCoord)
    val numberOfPieces: Int = Math.max((distance / splitSizeInMeters).toInt, 1)

    val xDeltaVector = (endCoord.getX - startCoord.getX) / numberOfPieces
    val yDeltaVector = (endCoord.getY - startCoord.getY) / numberOfPieces

    val resultVector: ArrayBuffer[DataPoint] = collection.mutable.ArrayBuffer()

    for (i <- 0 to numberOfPieces) {
      resultVector += DataPoint(
        linkId,
        new Coord(startCoord.getX + i * xDeltaVector, startCoord.getY + i * yDeltaVector),
        ArrayBuffer()
      )
    }
    resultVector
  }

}

case class DataPoint(
                      linkId: DataId,
                      coord: Coord,
                      closestAttractedDataPoint: ArrayBuffer[DataPoint]
                    )
case class DataId(id: String) {
  def getLinkId = {
    Id.createLinkId(id)
  }
}
