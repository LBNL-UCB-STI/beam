package beam.agentsim.agents.rideHail.allocationManagers

import java.awt.Color

import beam.agentsim.agents.rideHail.RideHailingManager.RideHailingAgentLocation
import beam.agentsim.agents.rideHail.{RideHailingManager, TNCIterationStats}
import beam.router.BeamRouter.Location
import beam.utils._
import org.matsim.api.core.v01.{Coord, Id}
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

  def filterOutAlreadyRepositioningVehiclesIfEnoughAlternativeIdleVehiclesAvailable(idleVehicles: TrieMap[Id[Vehicle], RideHailingManager.RideHailingAgentLocation], maxNumberOfVehiclesToReposition: Int): Vector[RideHailingAgentLocation] = {
    val (idle, repositioning) = idleVehicles.values.toVector.partition(rideHailAgentLocation => rideHailingManager.modifyPassengerScheduleManager.isVehicleNeitherRepositioningNorProcessingReservation(rideHailAgentLocation.vehicleId))
    val result = if (idle.size < maxNumberOfVehiclesToReposition) {
      idle ++ repositioning.take(maxNumberOfVehiclesToReposition - idle.size)
    } else {
      idle
    }

    if (result.size < idleVehicles.values.size) {
      log.debug(s"filterOutAlreadyRepositioningVehiclesIfEnoughAlternativeIdleVehiclesAvailable: reduced set by ${idleVehicles.values.size - result.size}")
    }

    result
  }


  var firstRepositioningOfDay = true


  var boundsCalculator: Option[BoundsCalculator] = None
  var firstRepositionCoordsOfDay: Option[(Coord, Coord)] = None

  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = {

    tncIterationStats match {
      case Some(tncIterStats) =>
        // iteration >0
        //tncIterationStats.getRideHailStatsInfo()

        val idleVehicles = rideHailingManager.getIdleVehicles
        val fleetSize = rideHailingManager.resources.size

        val repositioningConfig = rideHailingManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes
        // TODO: get proper number here from rideHailManager
        val timeWindowSizeInSecForDecidingAboutRepositioning = repositioningConfig.timeWindowSizeInSecForDecidingAboutRepositioning
        val percentageOfVehiclesToReposition = repositioningConfig.percentageOfVehiclesToReposition
        var maxNumberOfVehiclesToReposition = (fleetSize * percentageOfVehiclesToReposition).toInt

        var repositionCircleRadiusInMeters = repositioningConfig.repositionCircleRadiusInMeters
        var minimumNumberOfIdlingVehiclesThresholdForRepositioning = repositioningConfig.minimumNumberOfIdlingVehiclesThresholdForRepositioning

        val allowIncreasingRadiusIfDemandInRadiusLow = repositioningConfig.allowIncreasingRadiusIfDemandInRadiusLow
        val minDemandPercentageInRadius = repositioningConfig.minDemandPercentageInRadius



        if (firstRepositioningOfDay && tick > 0 && rideHailingManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.initialLocation.name.equalsIgnoreCase(RideHailingManager.INITIAL_RIDEHAIL_LOCATION_ALL_AT_CENTER)) {
          // allow more aggressive repositioning at start of day
          minimumNumberOfIdlingVehiclesThresholdForRepositioning = 0
          repositionCircleRadiusInMeters = 100 * 1000
          maxNumberOfVehiclesToReposition = idleVehicles.size
          firstRepositioningOfDay = false
        }

        //tncIterationStats.printMap()

        if (tick > 0) {
          // ignoring tick 0, as no vehicles checked in at that time
          assert(maxNumberOfVehiclesToReposition > 0, "Using RepositioningLowWaitingTimes allocation Manager but percentageOfVehiclesToReposition results in 0 respositioning - use Default Manager if not repositioning needed")
        }

        var vehiclesToReposition = filterOutAlreadyRepositioningVehiclesIfEnoughAlternativeIdleVehiclesAvailable(idleVehicles, minimumNumberOfIdlingVehiclesThresholdForRepositioning)

        vehiclesToReposition = tncIterStats.getVehiclesWhichAreBiggestCandidatesForIdling(idleVehicles, maxNumberOfVehiclesToReposition, tick, timeWindowSizeInSecForDecidingAboutRepositioning, minimumNumberOfIdlingVehiclesThresholdForRepositioning)

        repositionCircleRadiusInMeters = tncIterStats.getUpdatedCircleSize(vehiclesToReposition, repositionCircleRadiusInMeters, tick, timeWindowSizeInSecForDecidingAboutRepositioning, minDemandPercentageInRadius, allowIncreasingRadiusIfDemandInRadiusLow)


        //val whichTAZToRepositionTo: Vector[(Id[Vehicle], Location)] = if (repositioningMethod.equalsIgnoreCase("basedOnWaitingTimeGravity")){
        // tncIterStats.repositionToBasedOnWaitingTimesGravity(vehiclesToReposition, repositionCircleRadiusInMeters, tick, timeWindowSizeInSecForDecidingAboutRepositioning, rideHailingManager.beamServices)

        // TAZ1 -> waitingTime


        //} else {

        // define max TAZ to consider -keep to 10

        // keep same number as vehicles

        // add keepMaxTopNScores (TODO)

        val whichTAZToRepositionTo: Vector[(Id[Vehicle], Location)] = tncIterStats.reposition(vehiclesToReposition, repositionCircleRadiusInMeters, tick, timeWindowSizeInSecForDecidingAboutRepositioning, rideHailingManager.beamServices)
        //}


        if (vehiclesToReposition.nonEmpty) {
          DebugLib.emptyFunctionForSettingBreakPoint()
        }

        if (whichTAZToRepositionTo.nonEmpty) {
          DebugLib.emptyFunctionForSettingBreakPoint()
        }


        val produceDebugImages = true
        if (produceDebugImages && whichTAZToRepositionTo.nonEmpty) {
          if (tick > 0 && tick < 24 * 3600) {
            val spatialPlot = new SpatialPlot(1100, 1100, 50)

            //if (firstRepositionCoordOfDay.isDefined) {


            //  spatialPlot.addString(StringToPlot("A", firstRepositionCoordOfDay.get, Color.BLACK, 50))


            //spatialPlot.addString(StringToPlot("A", new Coord((boundsCalculator.get.minX+boundsCalculator.get.maxX)*4/10, (boundsCalculator.get.minY+boundsCalculator.get.maxY)*4/10), Color.BLACK, 50))
            //spatialPlot.addString(StringToPlot("B",new Coord((boundsCalculator.get.minX+boundsCalculator.get.maxX)*6/10, (boundsCalculator.get.minY+boundsCalculator.get.maxY)*6/10), Color.BLACK, 50))
            //spatialPlot.addInvisiblePointsForBoundary(new Coord((boundsCalculator.get.minX+boundsCalculator.get.maxX)*4/10, (boundsCalculator.get.minY+boundsCalculator.get.maxY)*4/10))
            //spatialPlot.addInvisiblePointsForBoundary(new Coord((boundsCalculator.get.minX+boundsCalculator.get.maxX)*6/10, (boundsCalculator.get.minY+boundsCalculator.get.maxY)*6/10))
            // }

            // for (taz:TAZ <- tncIterationStats.tazTreeMap.getTAZs()){
            //   spatialPlot.addInvisiblePointsForBoundary(taz.coord)
            // }

            // for (vehToRepso <- rideHailingManager.getIdleVehicles.values) {
            // spatialPlot.addPoint(PointToPlot(rideHailingManager.getRideHailAgentLocation(vehToRepso.vehicleId).currentLocation.loc, Color.GREEN, 10))
            // }

            val tazEntries = tncIterStats getCoordinatesWithRideHailStatsEntry(tick, tick + 3600)

            for (tazEntry <- tazEntries.filter(x => x._2.getDemandEstimate > 0)) {
              spatialPlot.addPoint(PointToPlot(tazEntry._1, Color.RED, 10))
              spatialPlot.addString(StringToPlot(s"(${tazEntry._2.getDemandEstimate},${tazEntry._2.sumOfWaitingTimes})", tazEntry._1, Color.RED, 20))
            }


            for (vehToRepso <- whichTAZToRepositionTo) {
              val lineToPlot = LineToPlot(rideHailingManager.getRideHailAgentLocation(vehToRepso._1).currentLocation.loc, vehToRepso._2, Color.blue, 3)
              spatialPlot.addLine(lineToPlot)

              //log.debug(s"spatialPlot.addLine:${lineToPlot.toString}")
              //spatialPlot.addPoint(PointToPlot(rideHailingManager.getRideHailAgentLocation(vehToRepso._1).currentLocation.loc, Color.YELLOW, 10))
            }


            /*if (firstRepositionCoordOfDay.isDefined) {
              spatialPlot.addString(StringToPlot("A", firstRepositionCoordOfDay.get, Color.BLACK, 50))
              spatialPlot.addString(StringToPlot("A", new Coord((spatialPlot.getBoundsCalculator().minX+spatialPlot.getBoundsCalculator().maxX)*4/10, (spatialPlot.getBoundsCalculator().minY+spatialPlot.getBoundsCalculator().maxY)*4/10), Color.BLACK, 50))
              spatialPlot.addString(StringToPlot("B", new Coord((spatialPlot.getBoundsCalculator().minX+spatialPlot.getBoundsCalculator().maxX)*6/10, (spatialPlot.getBoundsCalculator().minY+spatialPlot.getBoundsCalculator().maxY)*6/10), Color.BLACK, 50))
            } else {
              spatialPlot.addString(StringToPlot("A", firstRepositionCoordOfDay.get, Color.BLACK, 50))
            }*/

            if (!firstRepositionCoordsOfDay.isDefined) {
              firstRepositionCoordsOfDay = Some(rideHailingManager.getRideHailAgentLocation(whichTAZToRepositionTo.head._1).currentLocation.loc, whichTAZToRepositionTo.head._2)
            }

            spatialPlot.addString(StringToPlot("A", firstRepositionCoordsOfDay.get._1, Color.BLACK, 50))
            //spatialPlot.addString(StringToPlot("B", firstRepositionCoordsOfDay.get._2, Color.BLACK, 50))

            spatialPlot.writeImage(rideHailingManager.beamServices.matsimServices.getControlerIO.getIterationFilename(rideHailingManager.beamServices.iterationNumber, (tick / 3600 * 100).toInt / 100.0 + "locationOfAgentsInitally.png"))

            //if (!boundsCalculator.isDefined) {
            //  boundsCalculator = Some(spatialPlot.getBoundsCalculator())
            //}

          }
        }


        if (whichTAZToRepositionTo.nonEmpty) {
          log.debug(s"whichTAZToRepositionTo.size:${whichTAZToRepositionTo.size}")
        }


        whichTAZToRepositionTo
      case None =>
        // iteration 0

        val idleVehicles = rideHailingManager.getIdleVehicles

        if (firstRepositioningOfDay && idleVehicles.nonEmpty) {
          // these are zero distance repositionings
          // => this is a hack, as the tnc iteration stats does not know the initial position of any rideHailVehicle unless it has at least one pathTraversal during the day
          // this is needed to account for idling vehicles by TAZ, even if they are not moving during the whole day
          firstRepositioningOfDay = false
          val result = idleVehicles.map(idle => (idle._1, idle._2.currentLocation.loc)).toVector
          result
        } else {
          Vector()
        }
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
