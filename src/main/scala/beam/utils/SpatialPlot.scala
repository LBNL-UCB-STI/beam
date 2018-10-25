package beam.utils

import java.awt._
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.io.{BufferedWriter, File, FileWriter}

import beam.agentsim.agents.ridehail.RideHailAgent
import javax.imageio.ImageIO
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.mutable.ListBuffer
import scala.util.Random

case class PointToPlot(coord: Coord, color: Color, size: Int)
case class LineToPlot(startCoord: Coord, endCoord: Coord, color: Color, stroke: Int)
case class StringToPlot(text: String, coord: Coord, color: Color, fontSize: Int)
case class RideHailAgentInitCoord(agentId: Id[RideHailAgent], coord: Coord)

case class Bounds(minx: Double, miny: Double, maxx: Double, maxy: Double)

class BoundsCalculator() {
  var minX: Double = Double.MaxValue
  var maxX: Double = Double.MinValue
  var minY: Double = Double.MaxValue
  var maxY: Double = Double.MinValue

  def addPoint(coord: Coord): Unit = {
    minX = Math.min(minX, coord.getX)
    minY = Math.min(minY, coord.getY)
    maxX = Math.max(maxX, coord.getX)
    maxY = Math.max(maxY, coord.getY)
  }

  def getBound: Bounds = {
    Bounds(minX, minY, maxX, maxY)
  }

  def getImageProjectedCoordinates(
    originalCoord: Coord,
    width: Int,
    height: Int,
    frame: Int
  ): Coord = {
    val updatedWidth = width - 2 * frame
    val updatedHeight = height - 2 * frame

    if (minX == maxX) {
      new Coord(updatedWidth / 2, updatedHeight / 2)
    } else {
      new Coord(
        frame + (originalCoord.getX - minX) / (maxX - minX) * updatedWidth,
        frame + (originalCoord.getY - minY) / (maxY - minY) * updatedHeight
      )
    }
  }
}

// frame is good for text lables as they can be outside of the area otherwise
class SpatialPlot(width: Int, height: Int, frame: Int) {

  val pointsToPlot: ListBuffer[PointToPlot] = ListBuffer()

  val linesToPlot: ListBuffer[LineToPlot] = ListBuffer()

  val stringsToPlot: ListBuffer[StringToPlot] = ListBuffer()

  val rideHailAgentInitCoordBuffer: ListBuffer[RideHailAgentInitCoord] = ListBuffer()

  val bufferedImage =
    new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

  var boundsCalculator = new BoundsCalculator

  def setBoundsCalculator(boundsCalculator: BoundsCalculator) {
    this.boundsCalculator = boundsCalculator
  }

  def getBoundsCalculator: BoundsCalculator = {
    boundsCalculator
  }

  def addInvisiblePointsForBoundary(coord: Coord): Unit = {
    boundsCalculator.addPoint(coord)
  }

  def addLine(line: LineToPlot): Unit = {
    linesToPlot += line
    boundsCalculator.addPoint(line.startCoord)
    boundsCalculator.addPoint(line.endCoord)
  }

  def addString(stringToPlot: StringToPlot): Unit = {
    stringsToPlot += stringToPlot
    boundsCalculator.addPoint(stringToPlot.coord)
  }

  def addPoint(point: PointToPlot): Unit = {
    pointsToPlot += point
    boundsCalculator.addPoint(point.coord)
  }

  def addAgentWithCoord(rideHailAgentInitCoord: RideHailAgentInitCoord): Unit = {
    rideHailAgentInitCoordBuffer += rideHailAgentInitCoord
  }

  def writeCSV(path: String): Unit = {
    val out = new BufferedWriter(new FileWriter(path))
    val heading = "rideHailAgentID,xCoord,yCoord"
    out.write(heading)
    rideHailAgentInitCoordBuffer.foreach(rideHailAgentInitCoord => {
      val line = "\n" + rideHailAgentInitCoord.agentId + "," + rideHailAgentInitCoord.coord.getX + "," + rideHailAgentInitCoord.coord.getY
      out.write(line)
    })
    out.close()
  }

  def writeImage(path: String): Unit = {
    val graphics2d = bufferedImage.createGraphics()

    for (lineToPlot <- linesToPlot) {
      //val stroke = new BasicStroke(lineToPlot.stroke)
      //graphics2d.setStroke(stroke)
      graphics2d.setColor(lineToPlot.color)
      val projectedStartCoord =
        boundsCalculator.getImageProjectedCoordinates(lineToPlot.startCoord, width, height, frame)
      val projectedEndCoord =
        boundsCalculator.getImageProjectedCoordinates(lineToPlot.endCoord, width, height, frame)

      drawArrow(
        graphics2d,
        new Point2D.Double(projectedStartCoord.getX, projectedStartCoord.getY),
        new Point2D.Double(projectedEndCoord.getX, projectedEndCoord.getY),
        new BasicStroke(lineToPlot.stroke),
        new BasicStroke(lineToPlot.stroke * 10),
        lineToPlot.stroke * 10
      )

      //graphics2d.drawLine(projectedStartCoord.getX.toInt, projectedStartCoord.getY.toInt, projectedEndCoord.getX.toInt, projectedEndCoord.getY.toInt)
    }

    for (pointToPlot <- pointsToPlot) {
      graphics2d.setColor(pointToPlot.color)
      val projectedCoord =
        boundsCalculator.getImageProjectedCoordinates(pointToPlot.coord, width, height, frame)
      graphics2d.fillOval(
        projectedCoord.getX.toInt,
        projectedCoord.getY.toInt,
        pointToPlot.size,
        pointToPlot.size
      )
    }

    for (stringToPlot <- stringsToPlot) {
      val font = new Font("Serif", Font.PLAIN, stringToPlot.fontSize)
      graphics2d.setFont(font)
      graphics2d.setColor(stringToPlot.color)
      val projectedCoord =
        boundsCalculator.getImageProjectedCoordinates(stringToPlot.coord, width, height, frame)
      graphics2d.drawString(stringToPlot.text, projectedCoord.getX.toInt, projectedCoord.getY.toInt)
    }

    val index = path.lastIndexOf("/")
    val outDir = new File(path.substring(0, index))
    if (!outDir.exists()) outDir.mkdirs()
    ImageIO.write(bufferedImage, "PNG", new File(path))
  }

  def drawArrow(
    gfx: Graphics2D,
    start: Point2D,
    end: Point2D,
    lineStroke: Stroke,
    arrowStroke: Stroke,
    arrowSize: Float
  ): Unit = {
    import java.awt.geom.GeneralPath

    val startx = start.getX
    val starty = start.getY

    gfx.setStroke(arrowStroke)
    val deltax = startx - end.getX
    var result = .0
    if (deltax == 0.0d) result = Math.PI / 2
    else
      result = Math.atan((starty - end.getY) / deltax) + (if (startx < end.getX)
                                                            Math.PI
                                                          else 0)

    val angle = result

    val arrowAngle = Math.PI / 12.0d

    val x1 = arrowSize * Math.cos(angle - arrowAngle)
    val y1 = arrowSize * Math.sin(angle - arrowAngle)
    val x2 = arrowSize * Math.cos(angle + arrowAngle)
    val y2 = arrowSize * Math.sin(angle + arrowAngle)

    val cx = (arrowSize / 2.0f) * Math.cos(angle)
    val cy = (arrowSize / 2.0f) * Math.sin(angle)

    val polygon = new GeneralPath
    polygon.moveTo(end.getX, end.getY)
    polygon.lineTo(end.getX + x1, end.getY + y1)
    polygon.lineTo(end.getX + x2, end.getY + y2)
    polygon.closePath()
    gfx.fill(polygon)

    gfx.setStroke(lineStroke)
    gfx.drawLine(
      startx.toInt,
      starty.toInt,
      (end.getX + cx).asInstanceOf[Int],
      (end.getY + cy).asInstanceOf[Int]
    )
  }

}

object SpatialPlot extends App {
  /*
    val bi = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_ARGB);

    val ig2 = bi.createGraphics();
    //Draw some lines to the graphic


    //ig2.fillOval("sfdsfa", Random.nextFloat() * 1000, Random.nextFloat() * 1000)

    ig2.setColor(Color.BLACK)
    for (i <- 1 until 10) {
      ig2.fillOval(Random.nextInt(1000), Random.nextInt(1000), 5, 5)
    }

    ig2.setColor(Color.BLUE)
    for (i <- 1 until 10) {
      ig2.fillOval(Random.nextInt(1000), Random.nextInt(1000), 5, 5)
    }


    //ig2.drawLine(x1,y1,x2,y2);
    //ig2.drawLine(x2,y2,x3,y3);
    //...

    //Export the result to a file
    ImageIO.write(bi, "PNG", new File("c:\\temp\\name.png"));
   */

  val spatialPlot = new SpatialPlot(1000, 1000, 20)

  (1 until 100).foreach { _ =>
    spatialPlot.addPoint(
      PointToPlot(new Coord(Random.nextDouble(), Random.nextDouble()), Color.blue, 5)
    )
  }

  spatialPlot.addLine(
    LineToPlot(
      new Coord(Random.nextDouble(), Random.nextDouble()),
      new Coord(Random.nextDouble(), Random.nextDouble()),
      Color.blue,
      2
    )
  )

  spatialPlot.addString(
    StringToPlot("X", new Coord(Random.nextDouble(), Random.nextDouble()), Color.green, 100)
  )

  spatialPlot.writeImage("c:\\temp\\name.png")

}
