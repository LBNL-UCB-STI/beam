package beam.utils

import java.awt._
import java.io.{BufferedWriter, FileWriter}
import beam.agentsim.agents.ridehail.RideHailAgent
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.mutable.ListBuffer

case class PointToPlot(coord: Coord, color: Color, size: Int)
case class LineToPlot(startCoord: Coord, endCoord: Coord, color: Color, stroke: Int)
case class StringToPlot(text: String, coord: Coord, color: Color, fontSize: Int)
case class RideHailAgentInitCoord(agentId: Id[RideHailAgent], coord: Coord)

case class Bounds(minx: Double, miny: Double, maxx: Double, maxy: Double)

// frame is good for text labels as they can be outside of the area otherwise
class SpatialPlot(width: Int, height: Int, frame: Int) extends Plot(width, height, frame) {

  val rideHailAgentInitCoordBuffer: ListBuffer[RideHailAgentInitCoord] = ListBuffer()

  def addAgentWithCoord(rideHailAgentInitCoord: RideHailAgentInitCoord): Unit = {
    rideHailAgentInitCoordBuffer += rideHailAgentInitCoord
  }

  def writeCSV(path: String): Unit = {
    val out = new BufferedWriter(new FileWriter(path))
    val heading = "rideHailAgentID,xCoord,yCoord"
    out.write(heading)
    rideHailAgentInitCoordBuffer.foreach(rideHailAgentInitCoord => {
      val line =
        "\n" + rideHailAgentInitCoord.agentId + "," + rideHailAgentInitCoord.coord.getX + "," + rideHailAgentInitCoord.coord.getY
      out.write(line)
    })
    out.close()
  }

}
