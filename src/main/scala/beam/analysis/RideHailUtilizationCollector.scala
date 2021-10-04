package beam.analysis

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent, ReplanningEvent}
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.utils.csv.CsvWriter
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler

import scala.collection.immutable.SortedSet
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Try
import scala.util.control.NonFatal

case class RideInfo(
  vehicleId: Id[BeamVehicle],
  time: Int,
  startCoord: Coord,
  endCoord: Coord,
  numOfPassengers: Int,
  primaryFuelLevel: Double
)

case class RideHailHistoricalData(
  notMovedAtAll: Set[Id[BeamVehicle]],
  movedWithoutPassenger: Set[Id[BeamVehicle]],
  movedWithPassengers: Set[Id[BeamVehicle]],
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
  rideHailPooledChoices: Int,
  rideHailPooledInAlternatives: Int,
  totalModeChoices: Int,
  replanningReasonToTotalCountMap: Map[String, Int]
)

class RideHailUtilizationCollector(beamSvc: BeamServices)
    extends BasicEventHandler
    with IterationEndsListener
    with LazyLogging {
  val shouldDumpRides: Boolean = true
  private val rides: ArrayBuffer[RideInfo] = ArrayBuffer()
  private val utilizations: ArrayBuffer[Utilization] = ArrayBuffer()
  private var rideHailChoices: Int = 0
  private var rideHailPooledChoices: Int = 0
  private var rideHailInAlternatives: Int = 0
  private var rideHailPooledInAlternatives: Int = 0
  private var totalModeChoices: Int = 0
  private val replanningReasonToTotalCountMap: mutable.Map[String, Int] = new mutable.HashMap[String, Int]()

  val commonHeaders: Vector[String] = Vector(
    "iteration",
    "nonEmptyRides",
    "totalRides",
    "movedPassengers",
    "rideHailModeChoices",
    "rideHailInAlternatives",
    "totalModeChoices"
  )

  logger.info(s"Created RideHailUtilizationCollector with hashcode: ${this.hashCode()}")

  override def handleEvent(event: Event): Unit = {
    event match {
      case pte: PathTraversalEvent if pte.vehicleId.toString.contains("rideHailVehicle-") =>
        handle(pte)
      case mc: ModeChoiceEvent =>
        if (mc.mode == BeamMode.RIDE_HAIL.value)
          rideHailChoices += 1
        else if (mc.mode == BeamMode.RIDE_HAIL_POOLED.value)
          rideHailPooledChoices += 1
        if (mc.availableAlternatives == "RIDE_HAIL" || mc.availableAlternatives.contains("RIDE_HAIL:"))
          rideHailInAlternatives += 1
        if (mc.availableAlternatives.contains("RIDE_HAIL_POOLED"))
          rideHailPooledInAlternatives += 1
        totalModeChoices += 1
      case replanningEvent: ReplanningEvent =>
        val shouldProcess = replanningEvent.getReason.contains("RIDE_HAIL") || replanningEvent.getReason.contains(
          "RIDE_HAIL_POOLED"
        )
        if (shouldProcess) {
          val cnt = replanningReasonToTotalCountMap.getOrElse(replanningEvent.getReason, 0) + 1
          replanningReasonToTotalCountMap.update(replanningEvent.getReason, cnt)
        }
      case _ =>
    }
  }

  override def reset(iteration: Int): Unit = {
    logger.info(s"There were ${rides.length} ride-hail rides for iteration $iteration")
    rides.clear()
    rideHailChoices = 0
    rideHailPooledChoices = 0
    rideHailInAlternatives = 0
    rideHailPooledInAlternatives = 0
    totalModeChoices = 0
    replanningReasonToTotalCountMap.clear()
  }

  def handle(pte: PathTraversalEvent): RideInfo = {
    // Yes, PathTraversalEvent contains coordinates in WGS
    val startCoord = beamSvc.geo.wgs2Utm(new Coord(pte.startX, pte.startY))
    val endCoord = beamSvc.geo.wgs2Utm(new Coord(pte.endX, pte.endY))
    val vri =
      RideInfo(pte.vehicleId, pte.time.toInt, startCoord, endCoord, pte.numberOfPassengers, pte.endLegPrimaryFuelLevel)
    rides += vri
    vri
  }

  def calcUtilization(iteration: Int): Utilization = {
    val numOfPassengersToTheNumberOfRides: Map[Int, Int] = rides
      .groupBy(x => x.numOfPassengers)
      .map { case (numOfPassengers, xs) =>
        numOfPassengers -> xs.size
      }

    val vehicleToRides = rides.groupBy(x => x.vehicleId)

    val numOfRidesToVehicleId: Seq[(Int, Id[BeamVehicle])] = vehicleToRides
      .map { case (vehId, xs) =>
        vehId -> xs.count(_.numOfPassengers > 0)
      }
      .toSeq
      .map { case (vehId, nRides) =>
        nRides -> vehId
      }
    val ridesToVehicles = numOfRidesToVehicleId
      .groupBy { case (nRides, _) => nRides }
      .map { case (nRides, xs) =>
        nRides -> xs.map(_._2).size
      }

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
      rideHailPooledChoices = rideHailPooledChoices,
      rideHailPooledInAlternatives = rideHailPooledInAlternatives,
      totalModeChoices = totalModeChoices,
      replanningReasonToTotalCountMap = replanningReasonToTotalCountMap.toMap
    )
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
            |numberOfRidesServedByNumberOfVehicles: $sorted
            |rideHailChoices: ${utilization.rideHailModeChoices}
            |rideHailInAlternatives: ${utilization.rideHailInAlternatives}
            |rideHailPooledChoices: ${utilization.rideHailPooledChoices}
            |rideHailPooledInAlternatives: ${utilization.rideHailPooledInAlternatives}
            |totalModeChoices: ${utilization.totalModeChoices}
            |replannings: ${utilization.replanningReasonToTotalCountMap}""".stripMargin
    logger.info(msg)

    if (shouldDumpRides) {
      Try(writeRides()).recover { case ex =>
        logger.error(s"writeRides failed with: ${ex.getMessage}", ex)
      }
    }

    Try(writeUtilization()).recover { case ex =>
      logger.error(s"writeUtilization failed with: ${ex.getMessage}", ex)
    }

    val movedWithoutPassenger = RideHailUtilizationCollector.getMovedWithoutPassenger(rides)
    val movedWithPassengers = RideHailUtilizationCollector.getRidesWithPassengers(rides)
    val movedVehicleIds = movedWithPassengers.map(_.vehicleId).toSet

    logger.info(s"""|movedWithoutPassenger: ${movedWithoutPassenger.size}
                    |movedWithPassengers: ${movedWithPassengers.size}
                    |movedVehicleIds(distinct): ${movedVehicleIds.size}""".stripMargin)
  }

  def writeRides(): Unit = {
    val filePath = beamSvc.matsimServices.getControlerIO.getIterationFilename(
      beamSvc.matsimServices.getIterationNumber,
      "ridehailRides.csv.gz"
    )

    val csvWriter =
      new CsvWriter(filePath, Vector("vehicleId", "time", "startX", "startY", "endX", "endY", "numberOfPassengers"))
    try {
      val vehicleToRides = rides.groupBy(x => x.vehicleId)

      val ordered = vehicleToRides
        .map { case (vehId, xs) =>
          vehId -> xs.sortBy(x => x.time)
        }
        .toVector
        .sortBy { case (vehId, _) => vehId }

      ordered.foreach { case (_, sortedRides) =>
        sortedRides.foreach { ri =>
          csvWriter.write(
            ri.vehicleId,
            ri.time,
            ri.startCoord.getX,
            ri.startCoord.getY,
            ri.endCoord.getX,
            ri.endCoord.getY,
            ri.numOfPassengers
          )
        }
      }
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Could not write ride-hail rides to '$filePath': ${ex.getMessage}", ex)
    } finally {
      csvWriter.close()
    }
  }

  def writeUtilization(): Unit = {
    val filePath = beamSvc.matsimServices.getControlerIO.getOutputFilename("rideHailRideUtilization.csv")

    val allRides = SortedSet(utilizations.flatMap(_.numberOfRidesServedByNumberOfVehicles.keys): _*)
    val allPassengers = SortedSet(utilizations.flatMap(_.numOfPassengersToTheNumberOfRides.keys): _*)
    val rideHeaders = allRides.map(rideNumber => s"numberOfVehiclesServed${rideNumber}Rides")
    val passengerHeaders = allPassengers.map(passengers => s"${passengers}PassengersToTheNumberOfRides")

    val csvWriter = new CsvWriter(filePath, commonHeaders ++ rideHeaders ++ passengerHeaders)
    try {
      utilizations.foreach { utilization =>
        csvWriter.writeColumn(utilization.iteration)
        csvWriter.writeColumn(utilization.nonEmptyRides)
        csvWriter.writeColumn(utilization.totalRides)
        csvWriter.writeColumn(utilization.movedPassengers)
        csvWriter.writeColumn(utilization.rideHailModeChoices)
        csvWriter.writeColumn(utilization.rideHailInAlternatives)
        csvWriter.writeColumn(utilization.totalModeChoices)
        allRides.foreach { rides =>
          csvWriter.writeColumn(utilization.numberOfRidesServedByNumberOfVehicles.getOrElse(rides, 0))
        }
        if (allPassengers.nonEmpty) {
          allPassengers.foreach { passengers =>
            val isLastColumn = allPassengers.last == passengers
            csvWriter.writeColumn(
              utilization.numOfPassengersToTheNumberOfRides.getOrElse(passengers, 0),
              shouldAddDelimiter = !isLastColumn
            )
          }
        }
        csvWriter.writeNewLine()
      }
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Could not write ride-hail utilization to '$filePath': ${ex.getMessage}", ex)
    } finally {
      csvWriter.close()
    }
  }
}

object RideHailUtilizationCollector {

  def getMovedWithoutPassenger(rides: IndexedSeq[RideInfo]): Set[Id[BeamVehicle]] = {
    rides
      .groupBy { x =>
        x.vehicleId
      }
      .filter { case (_, xs) =>
        xs.forall(vri => vri.numOfPassengers == 0)
      }
      .keySet
  }

  def getRidesWithPassengers(rides: IndexedSeq[RideInfo]): IndexedSeq[RideInfo] = {
    val notMoved: Set[Id[BeamVehicle]] = getMovedWithoutPassenger(rides)
    val moved: IndexedSeq[RideInfo] = rides.filterNot(vri => notMoved.contains(vri.vehicleId))
    moved
  }
}
