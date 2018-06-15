package beam.agentsim.agents.rideHail.allocationManagers

import beam.agentsim.agents.rideHail.RideHailingManager.RideHailingAgentLocation
import beam.agentsim.agents.rideHail.{RideHailingManager, TNCIterationStats}
import beam.router.BeamRouter.Location
import beam.utils.DebugLib
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.concurrent.TrieMap

class RepositioningLowWaitingTimes(val rideHailingManager: RideHailingManager, tncIterationStats: Option[TNCIterationStats]) extends RideHailResourceAllocationManager {

  val isBufferedRideHailAllocationMode = false

  def proposeVehicleAllocation(vehicleAllocationRequest: VehicleAllocationRequest): Option[VehicleAllocation] = {
    None
  }

  def allocateVehicles(allocationsDuringReservation: Vector[(VehicleAllocationRequest, Option[VehicleAllocation])]): Vector[(VehicleAllocationRequest, Option[VehicleAllocation])] = {
    log.error("batch procesbsing is not implemented for DefaultRideHailResourceAllocationManager")
    allocationsDuringReservation
  }

  def filterOutAlreadyRepositioningVehiclesIfEnoughAlternativeIdleVehiclesAvailable(idleVehicles: TrieMap[Id[Vehicle], RideHailingManager.RideHailingAgentLocation], maxNumberOfVehiclesToReposition:Int): Vector[RideHailingAgentLocation] ={
    val (idle,repositioning)=idleVehicles.values.toVector.partition( rideHailAgentLocation => rideHailingManager.modifyPassengerScheduleManager.isVehicleNeitherRepositioningNorProcessingReservation(rideHailAgentLocation.vehicleId))
    val result= if (idle.size< maxNumberOfVehiclesToReposition){
      idle ++ repositioning.take(maxNumberOfVehiclesToReposition-idle.size)
    } else {
      idle
    }

    if (result.size<idleVehicles.values.size){
      log.debug(s"filterOutAlreadyRepositioningVehiclesIfEnoughAlternativeIdleVehiclesAvailable: reduced set by ${idleVehicles.values.size-result.size}")
    }

    result
  }

  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = {

    tncIterationStats match {
      case Some(tncIterationStats) =>
        // iteration >0
        //tncIterationStats.getRideHailStatsInfo()


        val idleVehicles = rideHailingManager.getIdleVehicles
        val fleetSize = rideHailingManager.resources.size // TODO: get proper number here from rideHailManager
      val timeWindowSizeInSecForDecidingAboutRepositioning = rideHailingManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.timeWindowSizeInSecForDecidingAboutRepositioning
        val percentageOfVehiclesToReposition = rideHailingManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.percentageOfVehiclesToReposition
        val maxNumberOfVehiclesToReposition = (fleetSize * percentageOfVehiclesToReposition).toInt

        var repositionCircleRadisInMeters = rideHailingManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.repositionCircleRadisInMeters
        val minimumNumberOfIdlingVehiclesThreshholdForRepositioning = rideHailingManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.minimumNumberOfIdlingVehiclesThreshholdForRepositioning

        val allowIncreasingRadiusIfMostDemandOutside = true
        val minReachableDemandByVehiclesSelectedForReposition = 0.1



        //tncIterationStats.printMap()

        assert(maxNumberOfVehiclesToReposition > 0, "Using RepositioningLowWaitingTimes allocation Manager but percentageOfVehiclesToReposition results in 0 respositioning - use Default Manager if not repositioning needed")



        var vehiclesToReposition=filterOutAlreadyRepositioningVehiclesIfEnoughAlternativeIdleVehiclesAvailable(idleVehicles,minimumNumberOfIdlingVehiclesThreshholdForRepositioning)

        vehiclesToReposition = tncIterationStats.getVehiclesWhichAreBiggestCandidatesForIdling(idleVehicles, maxNumberOfVehiclesToReposition, tick, timeWindowSizeInSecForDecidingAboutRepositioning,minimumNumberOfIdlingVehiclesThreshholdForRepositioning)

        repositionCircleRadisInMeters = tncIterationStats.getUpdatedCircleSize(vehiclesToReposition, repositionCircleRadisInMeters, tick, timeWindowSizeInSecForDecidingAboutRepositioning,minReachableDemandByVehiclesSelectedForReposition,allowIncreasingRadiusIfMostDemandOutside)


        val whichTAZToRepositionTo: Vector[(Id[Vehicle], Location)] = tncIterationStats.whichCoordToRepositionTo(vehiclesToReposition, repositionCircleRadisInMeters, tick, timeWindowSizeInSecForDecidingAboutRepositioning, rideHailingManager.beamServices)

        if (vehiclesToReposition.nonEmpty) {
          DebugLib.emptyFunctionForSettingBreakPoint()
        }

        if (whichTAZToRepositionTo.nonEmpty) {
          DebugLib.emptyFunctionForSettingBreakPoint()
        }

        whichTAZToRepositionTo
      case None =>
      // iteration 0
        Vector()
    }


    // if (rideHailingManager.getIdleVehicles().size >= 2) {
    // val origin=rideHailingManager.getIdleVehicles().values.toVector
    //  val destination=scala.util.Random.shuffle(origin)
    // (for ((o,d)<-(origin zip destination)) yield (o.vehicleId,d.currentLocation.loc)) //.splitAt(4)._1
    // } else {
   // Vector()
    // }
  }
}




