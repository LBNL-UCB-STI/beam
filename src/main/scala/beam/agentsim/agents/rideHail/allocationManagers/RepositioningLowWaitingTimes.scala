package beam.agentsim.agents.rideHail.allocationManagers

import java.awt.{Color, Graphics2D}
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

import beam.agentsim.agents.rideHail.RideHailingManager.RideHailingAgentLocation
import beam.agentsim.agents.rideHail.{RideHailingManager, TNCIterationStats}
import beam.router.BeamRouter.Location
import beam.utils.{DebugLib, PointToPlot, SpatialPlot}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle

import scala.collection.concurrent.TrieMap
import scala.util.Random

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


  var firstRepositioningOfDay=true

  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = {

    tncIterationStats match {
      case Some(tncIterationStats) =>
        // iteration >0
        //tncIterationStats.getRideHailStatsInfo()

          val idleVehicles = rideHailingManager.getIdleVehicles
          val fleetSize = rideHailingManager.resources.size // TODO: get proper number here from rideHailManager
          val timeWindowSizeInSecForDecidingAboutRepositioning = rideHailingManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.timeWindowSizeInSecForDecidingAboutRepositioning
          val percentageOfVehiclesToReposition = rideHailingManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.percentageOfVehiclesToReposition
          var maxNumberOfVehiclesToReposition = (fleetSize * percentageOfVehiclesToReposition).toInt

          var repositionCircleRadisInMeters = rideHailingManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.repositionCircleRadisInMeters
          var minimumNumberOfIdlingVehiclesThreshholdForRepositioning = rideHailingManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.minimumNumberOfIdlingVehiclesThreshholdForRepositioning

          val allowIncreasingRadiusIfMostDemandOutside = true
          val minReachableDemandByVehiclesSelectedForReposition = 0.1

          if (firstRepositioningOfDay & tick>0){
            // allow more aggressive repositioning at start of day
            minimumNumberOfIdlingVehiclesThreshholdForRepositioning=0
            repositionCircleRadisInMeters=500 *1000
            maxNumberOfVehiclesToReposition=idleVehicles.size
            firstRepositioningOfDay=false
          }




          //tncIterationStats.printMap()

        if (tick>0){
          // ignoring tick 0, as no vehicles checked in at that time
          assert(maxNumberOfVehiclesToReposition > 0, "Using RepositioningLowWaitingTimes allocation Manager but percentageOfVehiclesToReposition results in 0 respositioning - use Default Manager if not repositioning needed")
        }

          var vehiclesToReposition = filterOutAlreadyRepositioningVehiclesIfEnoughAlternativeIdleVehiclesAvailable(idleVehicles, minimumNumberOfIdlingVehiclesThreshholdForRepositioning)

          vehiclesToReposition = tncIterationStats.getVehiclesWhichAreBiggestCandidatesForIdling(idleVehicles, maxNumberOfVehiclesToReposition, tick, timeWindowSizeInSecForDecidingAboutRepositioning, minimumNumberOfIdlingVehiclesThreshholdForRepositioning)

          repositionCircleRadisInMeters = tncIterationStats.getUpdatedCircleSize(vehiclesToReposition, repositionCircleRadisInMeters, tick, timeWindowSizeInSecForDecidingAboutRepositioning, minReachableDemandByVehiclesSelectedForReposition, allowIncreasingRadiusIfMostDemandOutside)


          val whichTAZToRepositionTo: Vector[(Id[Vehicle], Location)] = tncIterationStats.whichCoordToRepositionTo(vehiclesToReposition, repositionCircleRadisInMeters, tick, timeWindowSizeInSecForDecidingAboutRepositioning, rideHailingManager.beamServices)

          if (vehiclesToReposition.nonEmpty) {
            DebugLib.emptyFunctionForSettingBreakPoint()
          }

          if (whichTAZToRepositionTo.nonEmpty) {
            DebugLib.emptyFunctionForSettingBreakPoint()
          }


        val produceDebugImages = false
        if (produceDebugImages) {
          if (tick > 0 && tick.toInt % 3600 == 0 && tick < 24 * 3600) {
            val spatialPlot = new SpatialPlot(1000, 1000)

            for (vehToRepso <- rideHailingManager.getIdleVehicles.values) {
              spatialPlot.addPoint(PointToPlot(rideHailingManager.getRideHailAgentLocation(vehToRepso.vehicleId).currentLocation.loc, Color.GREEN, 10))
            }

              val tazEntries = tncIterationStats getCoordinatesWithRideHailStatsEntry(tick, tick + 3600)

              for (tazEntry <- tazEntries.filter(x => x._2.sumOfRequestedRides > 0)) {
                spatialPlot.addPoint(PointToPlot(tazEntry._1, Color.RED, 10 + Math.log(tazEntry._2.sumOfRequestedRides).toInt))
              }


            for (vehToRepso <- whichTAZToRepositionTo) {
              spatialPlot.addPoint(PointToPlot(rideHailingManager.getRideHailAgentLocation(vehToRepso._1).currentLocation.loc, Color.YELLOW, 10))
            }

            spatialPlot.writeImage(rideHailingManager.beamServices.matsimServices.getControlerIO.getIterationFilename(rideHailingManager.beamServices.iterationNumber, tick.toInt / 3600 + "locationOfAgentsInitally.png"))
          }
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




