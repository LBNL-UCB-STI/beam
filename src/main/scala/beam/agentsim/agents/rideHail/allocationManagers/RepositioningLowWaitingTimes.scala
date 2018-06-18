package beam.agentsim.agents.rideHail.allocationManagers

import java.awt.{Color, Graphics2D}
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

import beam.agentsim.agents.rideHail.RideHailingManager.RideHailingAgentLocation
import beam.agentsim.agents.rideHail.{RideHailingManager, TNCIterationStats}
import beam.agentsim.infrastructure.TAZ
import beam.router.BeamRouter.Location
import beam.utils.SpatialPlot.spatialPlot
import beam.utils._
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



  var boundsCalculator:Option[BoundsCalculator]=None
  var firstRepositionCoordsOfDay:Option[(Coord,Coord)]=None

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

          val allowIncreasingRadiusIfDemandInRadiusLow = true // beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.allowIncreasingRadiusIfDemandInRadiusLow
          val minDemandPercentageInRadius = 0.1 //beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.minDemandPercentageInRadius

          if (firstRepositioningOfDay && tick>0 && rideHailingManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.initialLocation.name.equalsIgnoreCase(RideHailingManager.INITIAL_RIDEHAIL_LOCATION_ALL_AT_CENTER)){
            // allow more aggressive repositioning at start of day
            minimumNumberOfIdlingVehiclesThreshholdForRepositioning=0
            repositionCircleRadisInMeters=100 *1000
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

          repositionCircleRadisInMeters = tncIterationStats.getUpdatedCircleSize(vehiclesToReposition, repositionCircleRadisInMeters, tick, timeWindowSizeInSecForDecidingAboutRepositioning, minDemandPercentageInRadius, allowIncreasingRadiusIfDemandInRadiusLow)


          val whichTAZToRepositionTo: Vector[(Id[Vehicle], Location)] = tncIterationStats.whichCoordToRepositionTo(vehiclesToReposition, repositionCircleRadisInMeters, tick, timeWindowSizeInSecForDecidingAboutRepositioning, rideHailingManager.beamServices)

          if (vehiclesToReposition.nonEmpty) {
            DebugLib.emptyFunctionForSettingBreakPoint()
          }

          if (whichTAZToRepositionTo.nonEmpty) {
            DebugLib.emptyFunctionForSettingBreakPoint()
          }


        val produceDebugImages = true
        if (produceDebugImages && !whichTAZToRepositionTo.isEmpty) {
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

            val tazEntries = tncIterationStats getCoordinatesWithRideHailStatsEntry(tick, tick + 3600)

            for (tazEntry <- tazEntries.filter(x => x._2.sumOfRequestedRides > 0)) {
              spatialPlot.addPoint(PointToPlot(tazEntry._1, Color.RED, 10 + Math.log(tazEntry._2.sumOfRequestedRides).toInt))
              spatialPlot.addString(StringToPlot(tazEntry._2.sumOfRequestedRides.toString, tazEntry._1, Color.RED, 50))
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

            if (!firstRepositionCoordsOfDay.isDefined){
              firstRepositionCoordsOfDay=Some(rideHailingManager.getRideHailAgentLocation(whichTAZToRepositionTo.head._1).currentLocation.loc,whichTAZToRepositionTo.head._2)
            }

            spatialPlot.addString(StringToPlot("A", firstRepositionCoordsOfDay.get._1, Color.BLACK, 50))
            //spatialPlot.addString(StringToPlot("B", firstRepositionCoordsOfDay.get._2, Color.BLACK, 50))

            spatialPlot.writeImage(rideHailingManager.beamServices.matsimServices.getControlerIO.getIterationFilename(rideHailingManager.beamServices.iterationNumber, (tick / 3600 * 100).toInt / 100.0 + "locationOfAgentsInitally.png"))

            //if (!boundsCalculator.isDefined) {
            //  boundsCalculator = Some(spatialPlot.getBoundsCalculator())
            //}

          }
        }


        if (whichTAZToRepositionTo.size>0){
          //log.debug(s"whichTAZToRepositionTo.size:${whichTAZToRepositionTo.size}")
        }



          whichTAZToRepositionTo
      case None =>
      // iteration 0

        val idleVehicles = rideHailingManager.getIdleVehicles

        if (firstRepositioningOfDay && !idleVehicles.isEmpty){
          // these are zero distance repositionings
          // => this is a hack, as the tnc iteration stats does not know the initial position of any rideHailVehicle unless it has at least one pathTraversal during the day
          // this is needed to account for idling vehicles by TAZ, even if they are not moving during the whole day
          firstRepositioningOfDay=false
          val result=idleVehicles.map( idle => (idle._1,idle._2.currentLocation.loc)).toVector
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




