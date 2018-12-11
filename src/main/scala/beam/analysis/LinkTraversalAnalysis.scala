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

/**
  * Analyze the link traversal events.
  * @param scenario beam scenario.
  * @param beamServices beam services.
  * @param outputDirectoryHierarchy output directory hierarchy.
  */
class LinkTraversalAnalysis @Inject()(
  private val scenario: Scenario,
  private val beamServices: BeamServices,
  outputDirectoryHierarchy: OutputDirectoryHierarchy
) extends BasicEventHandler
    with OutputDataDescriptor {

  private val outputFileBaseName = "linkTraversalAnalysis"
  private var analysisData: Array[LinkTraversalData] = Array()

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
          val linkTravelTimes: Array[String] = event.asInstanceOf[PathTraversalEvent].getLinkTravelTimes.split(",")
          val vehicleId = eventAttributes.getOrElse(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID, "")
          val vehicleType = eventAttributes.getOrElse(PathTraversalEvent.ATTRIBUTE_VEHICLE_TYPE, "")
          val pathArrivalTime = try {
            eventAttributes.getOrElse(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME, "").toLong
          } catch {
            case _: Exception => 0
          }
          val linkArrivalTimes: Seq[Long] = for (i <- linkTravelTimes.indices) yield {
            i match {
              case 0 => pathArrivalTime
              case _ =>
                pathArrivalTime + (try {
                  linkTravelTimes(i - 1).toLong
                } catch {
                  case _: Exception => 0
                })
            }
          }
          val networkLinks = scenario.getNetwork.getLinks.values().asScala
          val nextLinkIds = linkIds.toList.takeRight(linkIds.size - 1)
          if (linkIds.nonEmpty) {
            val data = linkIds zip nextLinkIds zip linkTravelTimes zip linkArrivalTimes flatMap { tuple =>
              val (((id, nextId), travelTime), arrivalTime) = tuple
              val nextLink: Option[Link] = networkLinks.find(x => x.getId.toString.equals(nextId))
              networkLinks.find(x => x.getId.toString.equals(id)) map { currentLink =>
                val freeFlowSpeed = currentLink.getFreespeed
                val linkCapacity = currentLink.getCapacity
                val averageSpeed = try {
                  currentLink.getLength / travelTime.toInt
                } catch {
                  case _: Exception => 0.0
                }
                val turnAtLinkEnd =
                  getDirection(vectorFromLink(currentLink), vectorFromLink(nextLink.getOrElse(currentLink)))
                val numberOfStops = if (turnAtLinkEnd.equalsIgnoreCase("NA")) 0 else 1
                (
                  id,
                  linkCapacity,
                  averageSpeed,
                  freeFlowSpeed,
                  arrivalTime,
                  vehicleId,
                  vehicleType,
                  turnAtLinkEnd,
                  numberOfStops
                )
              }
            }
            analysisData = analysisData ++ data
          }
        case _ =>
      }
    } catch {
      case e: Exception => e.printStackTrace()
    }
  }

  /**
    * Write the analysis at the end of the required iteration
    * @param event Iteration ends Event
    */
  def generateAnalysis(event: IterationEndsEvent): Unit = {
    val writeInterval: Int = beamServices.beamConfig.beam.outputs.writeLinkTraversalInterval
    val outputFilePath = outputDirectoryHierarchy.getIterationFilename(event.getIteration, outputFileBaseName + ".csv")
    if (writeInterval > 0 && event.getIteration == writeInterval)
      this.writeCSV(analysisData, outputFilePath)
  }

  /**
    * Helper method that writes the final data to a CSV file

    * @param data data to be written to the csv file
    * @param outputFilePath path to the CSV file
    */
  private def writeCSV(data: Array[LinkTraversalData], outputFilePath: String): Unit = {
    val bw = new BufferedWriter(new FileWriter(outputFilePath))
    try {
      val heading =
        "linkId,linkCapacity, averageSpeed, freeFlowSpeed, linkEnterTime, vehicleId, vehicleType, turnAtLinkEnd, numberOfStops"
      bw.append(heading + "\n")
      val content = (data.distinct
      map { e =>
        e._1 + ", " + e._2 + ", " + e._3 + ", " + e._4 + ", " + e._5 + ", " + e._6 + ", " + e._7 + ", " + e._8 + ", " + e._9
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
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName,
        relativePath,
        "averageSpeed",
        "Calculated average speed at which a vehicle drove through the link"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName,
        relativePath,
        "freeFlowSpeed",
        "Possible free speed at which a vehicle can drive through the link"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName,
        relativePath,
        "linkEnterTime",
        "Time at which vehicle entered the link"
      )
    )
    list.add(OutputDataDescription(this.getClass.getSimpleName, relativePath, "vehicleId", "Id of the vehicle"))
    list.add(OutputDataDescription(this.getClass.getSimpleName, relativePath, "vehicleType", "Type of the vehicle"))
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName,
        relativePath,
        "turnAtLinkEnd",
        "The direction to be taken by the vehicle at the end of link"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName,
        relativePath,
        "numberOfStops",
        "Number of stops at which the vehicle needs to be stopped"
      )
    )
    list
  }

  type LinkTraversalData = (String, Double, Double, Double, Long, String, String, String, Int)

  /**
    * Generates the direction vector for a given link
    * @param link link in the network
    * @return vector coordinates
    */
  private def vectorFromLink(link: Link): Coord = {
    new Coord(
      link.getToNode.getCoord.getX - link.getFromNode.getCoord.getX,
      link.getToNode.getCoord.getY - link.getFromNode.getCoord.getY
    )
  }

  /**
    * Get the desired direction to be taken , based on the angle between the coordinates
    * @param source source coordinates
    * @param destination destination coordinates
    * @return Direction to be taken ( L / HL / SL / R / SR / HR / S )
    */
  private def getDirection(source: Coord, destination: Coord): String = {
    if (!((source.getX == destination.getX) || (source.getY == destination.getY))) {
      val radians = Math.toRadians(Math.atan2(destination.getY - source.getY, destination.getX - source.getX))
      radians match {
        case _ if radians < 0.174533 || radians >= 6.10865 => "R" // Right
        case _ if radians >= 0.174533 & radians < 1.39626  => "SR" // Soft Right
        case _ if radians >= 1.39626 & radians < 1.74533   => "S" // Straight
        case _ if radians >= 1.74533 & radians < 2.96706   => "SL" // Soft Left
        case _ if radians >= 2.96706 & radians < 3.31613   => "L" // Left
        case _ if radians >= 3.31613 & radians < 3.32083   => "HL" // Hard Left
        case _ if radians >= 3.32083 & radians < 6.10865   => "HR" // Hard Right
        case _                                             => "S" // default => Straight
      }
    } else "S"
  }

}
