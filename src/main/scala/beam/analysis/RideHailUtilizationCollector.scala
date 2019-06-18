package beam.analysis

import java.io.{BufferedWriter, Writer}

import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent}
import beam.sim.BeamServices
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.utils.io.IOUtils
import org.matsim.vehicles.Vehicle

import scala.collection.mutable.ArrayBuffer

case class RideInfo(vehicleId: Id[Vehicle], time: Int, startCoord: Coord, endCoord: Coord, numOfPassengers: Int)
case class RideHailUtilization(
  notMovedAtAll: Set[Id[Vehicle]],
  movedWithoutPassenger: Set[Id[Vehicle]],
  movedWithPassengers: Set[Id[Vehicle]],
  rides: IndexedSeq[RideInfo]
)

class RideHailUtilizationCollector(beamSvc: BeamServices)
    extends BasicEventHandler
    with IterationEndsListener
    with LazyLogging {
  val shouldDumpRides: Boolean = true
  private val rides: ArrayBuffer[RideInfo] = ArrayBuffer()
  private var rideHailChoices: Int = 0
  private var rideHailInAlternatives: Int = 0
  private var totalModeChoices: Int = 0

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
    rides.clear()
    rideHailChoices = 0
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

  def calcUtilization(): Unit = {
    val numOfPassengersToTheNumberOfRides = rides
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
    val ridesToVehicles: Vector[(Int, Int)] = numOfRidesToVehicleId
      .groupBy { case (nRides, _) => nRides }
      .map {
        case (nRides, xs) =>
          nRides -> xs.map(_._2).size
      }
      .toVector
      .sortBy { case (nRides, _) => nRides }

    val totalNumberOfPassengerRides = rides.count(x => x.numOfPassengers > 0)

    val totalNumberOfMovedPassengers = rides
      .filter(x => x.numOfPassengers > 0)
      .map(_.numOfPassengers)
      .sum

    val msg =
      s"""
        |totalNumberOfPassengerRides: $totalNumberOfPassengerRides
        |totalNumberOfMovedPassengers: $totalNumberOfMovedPassengers
        |numOfPassengersToTheNumberOfRides: $numOfPassengersToTheNumberOfRides
        |ridesToVehicles: $ridesToVehicles
        |total rides: ${rides.length}
        |rideHailChoices: $rideHailChoices
        |rideHailInAlternatives: $rideHailInAlternatives
        |totalModeChoices: $totalModeChoices""".stripMargin
    logger.info(msg)
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
    calcUtilization()
    if (shouldDumpRides) {
      writeRides()
    }

    val movedWithoutPassenger = RideHailUtilizationCollector.getMovedWithoutPassenger(rides)
    val movedWithPassengers = RideHailUtilizationCollector.getRidesWithPassengers(rides)
    val movedVehicleIds = movedWithPassengers.map(_.vehicleId).toSet
    val neverMoved = beamSvc.rideHailState.getAllRideHailVehicles -- movedVehicleIds -- movedWithoutPassenger
    beamSvc.rideHailState.setRideHailUtilization(
      RideHailUtilization(neverMoved, movedWithoutPassenger, movedVehicleIds, rides.toArray[RideInfo])
    )
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
