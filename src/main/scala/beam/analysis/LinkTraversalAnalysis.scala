package beam.analysis

import java.io.{BufferedWriter, FileWriter}
import java.util

import beam.agentsim.events.PathTraversalEvent
import beam.sim.{BeamServices, OutputDataDescription}
import beam.utils.OutputDataDescriptor
import com.google.inject.Inject
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Coord, Scenario}
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.events.handler.BasicEventHandler

import scala.collection.JavaConverters._
import scala.collection.mutable

class LinkTraversalAnalysis @Inject()
(private val scenario: Scenario,
 private val beamServices: BeamServices,
 outputDirectoryHierarchy: OutputDirectoryHierarchy) extends BasicEventHandler with OutputDataDescriptor {

  private val outputFileBaseName = "linkTraversalAnalysis"
  private var analysisData : Array[LinkTraversalData] = Array()

  /**
    * Handles the PathTraversalEvent notification and generates the analysis data
    * @param event Event
    */
  override def handleEvent(event: Event): Unit = {
    try {
      event match {
        case pe if event.isInstanceOf[PathTraversalEvent] =>
          val eventAttributes: mutable.Map[String, String] = pe.getAttributes.asScala
          val linkIds = eventAttributes.getOrElse(PathTraversalEvent.ATTRIBUTE_LINK_IDS, "").split(",")
          val linkTravelTimes = eventAttributes.getOrElse(PathTraversalEvent.ATTRIBUTE_LINK_TRAVEL_TIMES, "").split(",")
          val vehicleId = eventAttributes.getOrElse(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID, "")
          val vehicleType = eventAttributes.getOrElse(PathTraversalEvent.ATTRIBUTE_VEHICLE_TYPE, "")
          val arrivalTime = eventAttributes.getOrElse(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME, "")
          val networkLinks = scenario.getNetwork.getLinks.values().asScala
          val nextLinkIds = linkIds.toList.takeRight(linkIds.size - 1)
          if (linkIds.nonEmpty) {
            val data = linkIds zip nextLinkIds zip linkTravelTimes flatMap { tuple =>
              val ((id, nextId), travelTime) = tuple
              val nextLink: Option[Link] = networkLinks.find(x => x.getId.toString.equals(nextId))
              networkLinks.find(x => x.getId.toString.equals(id)) map { currentLink =>
                val freeFlowSpeed = currentLink.getFreespeed
                val averageSpeed = try {
                  currentLink.getLength / travelTime.toInt
                } catch {
                  case _: Exception => 0.0
                }
                val turnAtLinkEnd = getDirection(currentLink.getCoord,nextLink.map(_.getCoord).getOrElse(new Coord(0.0,0.0)))
                val numberOfStops = if(turnAtLinkEnd.equalsIgnoreCase("NA")) 0 else 1
                (id,averageSpeed,freeFlowSpeed,arrivalTime,vehicleId,vehicleType,turnAtLinkEnd,numberOfStops)
              }
            }
            analysisData = analysisData ++ data
          }
        case _ =>
      }
    } catch {
      case e : Exception => e.printStackTrace()
    }
  }

  /**
    * Write the analysis at the end of the required iteration
    * @param event Iteration ends Event
    */
  def generateAnalysis(event: IterationEndsEvent): Unit = {
    val writeInterval: Int = beamServices.beamConfig.beam.outputs.writeLinkTraversalInterval
    println("Write interval is : " + writeInterval)
    val outputFilePath = outputDirectoryHierarchy.getIterationFilename(event.getIteration,outputFileBaseName + ".csv")
    if(writeInterval > 0 && event.getIteration == writeInterval)
      this.writeCSV(analysisData,outputFilePath)
  }

  /**
    * Helper method that writes the final data to a CSV file

    * @param data data to be written to the csv file
    * @param outputFilePath path to the CSV file
    */
  private def writeCSV(data: Array[LinkTraversalData], outputFilePath: String): Unit = {
    val bw = new BufferedWriter(new FileWriter(outputFilePath))
    try {
      val heading = "linkId, averageSpeed, freeFlowSpeed, linkEnterTime, vehicleId, vehicleType, turnAtLinkEnd, numberOfStops"
      bw.append(heading + "\n")
      val content = (data map { e =>
        e._1 + ", " + e._2 + ", " + e._3 + ", " + e._4 + ", " + e._5 + ", " + e._6 + ", " + e._7 + ", " + e._8
      }).mkString("\n")
      bw.append(content)
    } catch {
      case e: Exception =>
        e.printStackTrace()
    } finally {
      bw.close()
    }
  }

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  override def getOutputDataDescriptions: util.List[OutputDataDescription] = {
    val outputFilePath = outputDirectoryHierarchy.getIterationFilename(0, outputFileBaseName + ".csv")
    val outputDirPath = outputDirectoryHierarchy.getOutputPath
    val relativePath = outputFilePath.replace(outputDirPath, "")
    val list = new util.ArrayList[OutputDataDescription]
    list.add(OutputDataDescription(this.getClass.getSimpleName, relativePath, "linkId", "Id of the network link"))
    list.add(OutputDataDescription(this.getClass.getSimpleName, relativePath, "averageSpeed", "Calculated average speed at which a vehicle drove through the link"))
    list.add(OutputDataDescription(this.getClass.getSimpleName, relativePath, "freeFlowSpeed", "Possible free speed at which a vehicle can drive through the link"))
    list.add(OutputDataDescription(this.getClass.getSimpleName, relativePath, "linkEnterTime", "Time at which vehicle entered the link"))
    list.add(OutputDataDescription(this.getClass.getSimpleName, relativePath, "vehicleId", "Id of the vehicle"))
    list.add(OutputDataDescription(this.getClass.getSimpleName, relativePath, "vehicleType", "Type of the vehicle"))
    list.add(OutputDataDescription(this.getClass.getSimpleName, relativePath, "turnAtLinkEnd", "The direction to be taken by the vehicle at the end of link"))
    list.add(OutputDataDescription(this.getClass.getSimpleName, relativePath, "numberOfStops", "Number of stops at which the vehicle needs to be stopped"))
    list
  }

  type LinkTraversalData = (String, Double, Double, String, String, String, String, Int)

  /**
    * Computes the angle between two coordinates
    * @param source source coordinates
    * @param destination destination coordinates
    * @return angle
    */
  private def computeAngle(source : Coord, destination: Coord): Double = {
    val angle: Double = Math.toDegrees(Math.atan2(destination.getY - source.getY, destination.getX - source.getX))
    if (angle < 0) angle + 360 else angle
  }

  /**
    * Get the desired direction to be taken , based on the angle between the coordinates
    * @param source source coordinates
    * @param destination destination coordinates
    * @return Direction ( L / R / S / U / NA )
    */
  private def getDirection(source : Coord, destination: Coord): String = {
    if(!((source.getX == destination.getX) || (source.getY == destination.getY))) {
      val angle = this.computeAngle(source,destination)
      angle match {
        case r if angle > 0 & angle < 90 => "R"
        case l if angle >= 90 & angle < 180 => "L"
        case u if angle >= 180 && angle <=360 => "U"
        case s if angle == 90 => "S"
        case _ => "NA"
      }
    } else "NA"
  }

}
