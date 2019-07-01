package beam.analysis

import java.io.{BufferedWriter, Writer}

import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent}
import beam.sim.BeamServices
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.{IterationEndsEvent, ShutdownEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, ShutdownListener}
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.utils.io.IOUtils
import org.matsim.vehicles.Vehicle

import scala.collection.immutable.SortedSet
import scala.collection.mutable.ArrayBuffer

case class RideInfo(vehicleId: Id[Vehicle], time: Int, startCoord: Coord, endCoord: Coord, numOfPassengers: Int)
case class RideHailHistoricalData(
  notMovedAtAll: Set[Id[Vehicle]],
  movedWithoutPassenger: Set[Id[Vehicle]],
  movedWithPassengers: Set[Id[Vehicle]],
  rides: IndexedSeq[RideInfo]
)

case class Utilization(
  iteration: Int,
  nonEmptyRides: Int,
  totalRides: Int,
  movedPassengers: Int,
  numOfPassengersToTheNumberOfRides: Map[Int, Int],
  numberOfRidesServedByNumberOfVehicles: Map[Int, Int],
  rideHailModeChoices: Int,
  rideHailInAlternatives: Int,
  totalModeChoices: Int
)

class RideHailUtilizationCollector(beamSvc: BeamServices)
    extends BasicEventHandler
    with IterationEndsListener
    with ShutdownListener
    with LazyLogging {
  val shouldDumpRides: Boolean = true
  private val rides: ArrayBuffer[RideInfo] = ArrayBuffer()
  private val utilizations: ArrayBuffer[Utilization] = ArrayBuffer()
  private var rideHailChoices: Int = 0
  private var rideHailInAlternatives: Int = 0
  private var totalModeChoices: Int = 0
  logger.info(s"Created RideHailUtilizationCollector with hashcode: ${this.hashCode()}")

  override def handleEvent(event: Event): Unit = {
    event match {
      case pte: PathTraversalEvent if pte.vehicleId.toString.contains("rideHailVehicle-") =>
        handle(pte)
      case mc: ModeChoiceEvent =>
        if (mc.mode == "ride_hail")
          rideHailChoices += 1
        if (mc.availableAlternatives.contains("RIDE_HAIL"))
          rideHailInAlternatives += 1
        totalModeChoices += 1
      case _ =>
    }
  }

  override def reset(iteration: Int): Unit = {
    logger.info(s"There were ${rides.length} ride-hail rides for iteration $iteration")
    rides.clear()
    rideHailChoices = 0
    rideHailInAlternatives = 0
    totalModeChoices = 0
  }

  def handle(pte: PathTraversalEvent): RideInfo = {
    // Yes, PathTraversalEvent contains coordinates in WGS
    val startCoord = beamSvc.geo.wgs2Utm(new Coord(pte.startX, pte.startY))
    val endCoord = beamSvc.geo.wgs2Utm(new Coord(pte.endX, pte.endY))
    val vri = RideInfo(pte.vehicleId, pte.time.toInt, startCoord, endCoord, pte.numberOfPassengers)
    rides += vri
    vri
  }

  def calcUtilization(iteration: Int): Utilization = {
    val numOfPassengersToTheNumberOfRides: Map[Int, Int] = rides
      .groupBy(x => x.numOfPassengers)
      .map {
        case (numOfPassengers, xs) =>
          numOfPassengers -> xs.size
      }

    val vehicleToRides = rides.groupBy(x => x.vehicleId)

    val numOfRidesToVehicleId: Seq[(Int, Id[Vehicle])] = vehicleToRides
      .map {
        case (vehId, xs) =>
          vehId -> xs.count(_.numOfPassengers > 0)
      }
      .toSeq
      .map {
        case (vehId, nRides) =>
          nRides -> vehId
      }
    val ridesToVehicles = numOfRidesToVehicleId
      .groupBy { case (nRides, _) => nRides }
      .map {
        case (nRides, xs) =>
          nRides -> xs.map(_._2).size
      }
      .toMap

    val totalNumberOfNonEmptyRides = rides.count(x => x.numOfPassengers > 0)

    val totalNumberOfMovedPassengers = rides
      .filter(x => x.numOfPassengers > 0)
      .map(_.numOfPassengers)
      .sum

    Utilization(
      iteration = iteration,
      nonEmptyRides = totalNumberOfNonEmptyRides,
      totalRides = rides.length,
      movedPassengers = totalNumberOfMovedPassengers,
      numOfPassengersToTheNumberOfRides = numOfPassengersToTheNumberOfRides,
      numberOfRidesServedByNumberOfVehicles = ridesToVehicles,
      rideHailModeChoices = rideHailChoices,
      rideHailInAlternatives = rideHailInAlternatives,
      totalModeChoices = totalModeChoices
    )
  }

  def writeRides(): Unit = {
    val filePath = beamSvc.matsimServices.getControlerIO.getIterationFilename(
      beamSvc.matsimServices.getIterationNumber,
      "ridehailRides.csv.gz"
    )
    val fileHeader = "vehicleId,time,startX,startY,endX,endY,numberOfPassengers"
    implicit val bw: BufferedWriter = IOUtils.getBufferedWriter(filePath)
    bw.write(fileHeader)
    bw.newLine()

    try {
      val vehicleToRides = rides.groupBy(x => x.vehicleId)

      val ordered = vehicleToRides
        .map {
          case (vehId, xs) =>
            vehId -> xs.sortBy(x => x.time)
        }
        .toVector
        .sortBy { case (vehId, _) => vehId }

      ordered.foreach {
        case (_, sortedRides) =>
          sortedRides.foreach { ri =>
            writeColumnValue(ri.vehicleId)
            writeColumnValue(ri.time)
            writeColumnValue(ri.startCoord.getX)
            writeColumnValue(ri.startCoord.getY)
            writeColumnValue(ri.endCoord.getX)
            writeColumnValue(ri.endCoord.getY)
            writeColumnValue(ri.numOfPassengers, shouldAddSeparator = false)
            bw.newLine()
          }
      }
    } finally {
      bw.close()
    }
  }

  def writeColumnValue(value: Any, shouldAddSeparator: Boolean = true)(implicit wrt: Writer): Unit = {
    wrt.append(value.toString)
    if (shouldAddSeparator)
      wrt.append(",")
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    val utilization = calcUtilization(event.getIteration)
    utilizations += utilization

    val sorted = utilization.numberOfRidesServedByNumberOfVehicles.toVector.sortBy(x => x._1)
    val msg =
      s"""
            |nonEmptyRides: ${utilization.nonEmptyRides}
            |totalRides: ${utilization.totalRides}
            |movedPassengers: ${utilization.movedPassengers}
            |numOfPassengersToTheNumberOfRides: ${utilization.numOfPassengersToTheNumberOfRides}
            |numberOfRidesServedByNumberOfVehicles: ${sorted}
            |rideHailChoices: ${utilization.rideHailModeChoices}
            |rideHailInAlternatives: ${utilization.rideHailInAlternatives}
            |totalModeChoices: ${utilization.totalModeChoices}""".stripMargin
    logger.info(msg)

    if (shouldDumpRides) {
      writeRides()
    }

    val movedWithoutPassenger = RideHailUtilizationCollector.getMovedWithoutPassenger(rides)
    val movedWithPassengers = RideHailUtilizationCollector.getRidesWithPassengers(rides)
    val movedVehicleIds = movedWithPassengers.map(_.vehicleId).toSet
    val neverMoved = beamSvc.rideHailState.getAllRideHailVehicles -- movedVehicleIds -- movedWithoutPassenger
    beamSvc.rideHailState.setRideHailUtilization(
      RideHailHistoricalData(neverMoved, movedWithoutPassenger, movedVehicleIds, rides.toArray[RideInfo])
    )
  }

  override def notifyShutdown(event: ShutdownEvent): Unit = {
    val allRides = SortedSet(utilizations.flatMap(_.numberOfRidesServedByNumberOfVehicles.keys): _*)
    val allPassengers = SortedSet(utilizations.flatMap(_.numOfPassengersToTheNumberOfRides.keys): _*)

    val filePath = beamSvc.matsimServices.getControlerIO.getOutputFilename("rideHailRideUtilization.csv")
    val fileHeader = new StringBuffer()
    fileHeader.append("iteration,nonEmptyRides,totalRides,movedPassengers,")
    allRides.foreach { rideNumber =>
      fileHeader.append(s"numberOfVehiclesServed${rideNumber}Rides")
      fileHeader.append(",")
    }
    allPassengers.foreach { passengers =>
      fileHeader.append(s"${passengers}PassengersToTheNumberOfRides")
      fileHeader.append(",")
    }
    fileHeader.append("rideHailModeChoices")
    fileHeader.append(",")
    fileHeader.append("rideHailInAlternatives")
    fileHeader.append(",")
    fileHeader.append("totalModeChoices")

    implicit val bw: BufferedWriter = IOUtils.getBufferedWriter(filePath)
    try {
      bw.write(fileHeader.toString)
      bw.newLine()

      utilizations.foreach { utilization =>
        writeColumnValue(utilization.iteration)
        writeColumnValue(utilization.nonEmptyRides)
        writeColumnValue(utilization.totalRides)
        writeColumnValue(utilization.movedPassengers)

        allRides.foreach { rides =>
          writeColumnValue(utilization.numberOfRidesServedByNumberOfVehicles.getOrElse(rides, 0))
        }

        allPassengers.foreach { passengers =>
          writeColumnValue(utilization.numOfPassengersToTheNumberOfRides.getOrElse(passengers, 0))
        }
        writeColumnValue(utilization.rideHailModeChoices)
        writeColumnValue(utilization.rideHailInAlternatives)
        writeColumnValue(utilization.totalModeChoices, false)
        bw.newLine()
        bw.flush()
      }
    } finally {
      bw.close()
    }
  }
}

object RideHailUtilizationCollector {

  def getMovedWithoutPassenger(rides: IndexedSeq[RideInfo]): Set[Id[Vehicle]] = {
    rides
      .groupBy { x =>
        x.vehicleId
      }
      .filter {
        case (_, xs) =>
          xs.forall(vri => vri.numOfPassengers == 0)
      }
      .keySet
  }

  def getRidesWithPassengers(rides: IndexedSeq[RideInfo]): IndexedSeq[RideInfo] = {
    val notMoved: Set[Id[Vehicle]] = getMovedWithoutPassenger(rides)
    val moved: IndexedSeq[RideInfo] = rides.filterNot(vri => notMoved.contains(vri.vehicleId))
    moved
  }
}
