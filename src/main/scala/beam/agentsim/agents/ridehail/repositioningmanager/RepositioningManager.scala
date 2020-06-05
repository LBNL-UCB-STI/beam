package beam.agentsim.agents.ridehail.repositioningmanager

import beam.agentsim.agents.ridehail.RideHailManager
import beam.agentsim.agents.ridehail.RideHailVehicleManager.RideHailAgentLocation
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.router.BeamRouter.Location
import beam.sim.BeamServices
import org.matsim.api.core.v01.{Coord, Id}

import scala.reflect.ClassTag

abstract class RepositioningManager(
  private val beamServices: BeamServices,
  private val rideHailManager: RideHailManager
) {

  val repositionTimeout: Int =
    rideHailManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.repositioningManager.timeout

  def repositionVehicles(
    idleVehicles: scala.collection.Map[Id[BeamVehicle], RideHailAgentLocation],
    tick: Int
  ): Vector[(Id[BeamVehicle], Location)]
}

object RepositioningManager {

  def apply[T <: RepositioningManager](beamServices: BeamServices, rideHailManager: RideHailManager)(
    implicit ct: ClassTag[T]
  ): T = {
    val constructors = ct.runtimeClass.getDeclaredConstructors
    require(
      constructors.size == 1,
      s"Only one constructor is allowed for RepositioningManager, but $ct has ${constructors.length}"
    )
    constructors.head.newInstance(beamServices, rideHailManager).asInstanceOf[T]
  }
}

class DefaultRepositioningManager(val beamServices: BeamServices, val rideHailManager: RideHailManager)
    extends RepositioningManager(beamServices, rideHailManager) {
  override def repositionVehicles(
    idleVehicles: scala.collection.Map[Id[BeamVehicle], RideHailAgentLocation],
    tick: Int
  ): Vector[(Id[BeamVehicle], Location)] = Vector.empty
}

class TheSameLocationRepositioningManager(val beamServices: BeamServices, val rideHailManager: RideHailManager)
    extends RepositioningManager(beamServices, rideHailManager) {
  override def repositionVehicles(
    idleVehicles: scala.collection.Map[Id[BeamVehicle], RideHailAgentLocation],
    tick: Int
  ): Vector[(Id[BeamVehicle], Location)] = {
    rideHailManager.vehicleManager.getIdleVehiclesAndFilterOutExluded.map {
      case (id, rha) => (id, rha.currentLocationUTM.loc)
    }.toVector
  }
}

case class ClusterInfo(size: Int, coord: Coord, activitiesLocation: IndexedSeq[Coord])
