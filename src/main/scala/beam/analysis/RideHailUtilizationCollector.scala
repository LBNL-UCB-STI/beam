package beam.analysis

import beam.agentsim.events.PathTraversalEvent
import beam.sim.BeamServices
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.vehicles.Vehicle

import scala.collection.mutable.ArrayBuffer

case class VehicleRideInfo(vehicleId: Id[Vehicle], startCoord: Coord, endCoord: Coord, numOfPassengers: Int)
case class RideHailUtilization(notMovedAtAll: Set[Id[Vehicle]], movedWithoutPassenger: Set[Id[Vehicle]], movedWithPassengers: IndexedSeq[VehicleRideInfo])

class RideHailUtilizationCollector(beamSvc: BeamServices) extends BasicEventHandler with IterationEndsListener {
  private val rides: ArrayBuffer[VehicleRideInfo] = ArrayBuffer()

  override def handleEvent(event: Event): Unit = {
    event match {
      case pte: PathTraversalEvent if pte.vehicleId.toString.contains("rideHailVehicle-") =>
        handle(pte)
      case _ =>
    }
  }

  override def reset(iteration: Int): Unit = {
    rides.clear()
  }

  def handle(pte: PathTraversalEvent): VehicleRideInfo = {
    // Yes, PathTraversalEvent contains coordinates in WGS
    val startCoord = beamSvc.geo.wgs2Utm(new Coord(pte.startX, pte.startY))
    val endCoord = beamSvc.geo.wgs2Utm(new Coord(pte.endX, pte.endY))
    val vri = VehicleRideInfo(pte.vehicleId, startCoord, endCoord, pte.numberOfPassengers)
    rides += vri
    vri
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    val movedWithoutPassenger = RideHailUtilizationCollector.getMovedWithoutPassenger(rides)
    val movedWithPassengers = RideHailUtilizationCollector.getRidesWithPassengers(rides)
    val movedVehicleIds = movedWithPassengers.map(_.vehicleId).toSet
    val neverMoved = beamSvc.rideHailState.getAllRideHailVehicles -- movedVehicleIds -- movedWithoutPassenger
    beamSvc.rideHailState.setRideHailUtilization(RideHailUtilization(neverMoved, movedWithoutPassenger, movedWithPassengers))
  }
}

object RideHailUtilizationCollector {
  def getMovedWithoutPassenger(rides: IndexedSeq[VehicleRideInfo]): Set[Id[Vehicle]] = {
    rides.groupBy { x =>x.vehicleId}
      .filter { case (_, xs) =>
        xs.forall(vri => vri.numOfPassengers == 0)
      }.keySet
  }

  def getRidesWithPassengers(rides: IndexedSeq[VehicleRideInfo]): IndexedSeq[VehicleRideInfo] = {
    val notMoved: Set[Id[Vehicle]] = getMovedWithoutPassenger(rides)
    val moved: IndexedSeq[VehicleRideInfo] = rides.filterNot(vri => notMoved.contains(vri.vehicleId))
    moved
  }
}