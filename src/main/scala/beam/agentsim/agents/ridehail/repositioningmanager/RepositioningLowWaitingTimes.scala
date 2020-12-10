package beam.agentsim.agents.ridehail.repositioningmanager

import java.awt.Color

import beam.agentsim.agents.ridehail.RideHailManager
import beam.agentsim.agents.ridehail.RideHailVehicleManager.RideHailAgentLocation
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.router.BeamRouter.Location
import beam.sim.BeamServices
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.RideHail.AllocationManager
import beam.utils._
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Coord, Id}

class RepositioningLowWaitingTimes(val beamServices: BeamServices, val rideHailManager: RideHailManager)
    extends RepositioningManager(beamServices, rideHailManager)
    with LazyLogging {

  // Only override proposeVehicleAllocation if you wish to do something different from closest euclidean vehicle
  //  override def proposeVehicleAllocation(vehicleAllocationRequest: VehicleAllocationRequest): VehicleAllocationResponse
  var firstRepositioningOfDay = true
  var boundsCalculator: Option[BoundsCalculator] = None
  var firstRepositionCoordsOfDay: Option[(Coord, Coord)] = None

  val repositioningConfig: AllocationManager.RepositionLowWaitingTimes =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes

  // TODO: get proper number here from rideHailManager
  val timeWindowSizeInSecForDecidingAboutRepositioning: Double =
    repositioningConfig.timeWindowSizeInSecForDecidingAboutRepositioning

  val percentageOfVehiclesToReposition: Double =
    repositioningConfig.percentageOfVehiclesToReposition

  val maxNumberOfVehiclesToReposition: Int =
    (rideHailManager.fleetSize * percentageOfVehiclesToReposition).toInt

  var repositionCircleRadiusInMeters: Double =
    repositioningConfig.repositionCircleRadiusInMeters

  val minimumNumberOfIdlingVehiclesThresholdForRepositioning: Int =
    repositioningConfig.minimumNumberOfIdlingVehiclesThresholdForRepositioning

  val allowIncreasingRadiusIfDemandInRadiusLow: Boolean =
    repositioningConfig.allowIncreasingRadiusIfDemandInRadiusLow

  val minDemandPercentageInRadius: Double =
    repositioningConfig.minDemandPercentageInRadius

  override def repositionVehicles(
    idleVehicles: scala.collection.Map[Id[BeamVehicle], RideHailAgentLocation],
    tick: Int
  ): Vector[(Id[BeamVehicle], Location)] = {

    rideHailManager.tncIterationStats match {
      case Some(tncIterStats) =>
        //if (firstRepositioningOfDay && tick > 0 && rideHailManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.initialLocation.name.equalsIgnoreCase(RideHailManager.INITIAL_RIDE_HAIL_LOCATION_ALL_AT_CENTER)) {
        // allow more aggressive repositioning at start of day
        //minimumNumberOfIdlingVehiclesThresholdForRepositioning = 0
        //  repositionCircleRadiusInMeters = 100 * 1000
        //  maxNumberOfVehiclesToReposition = idleVehicles.size
        //} else if (firstRepositioningOfDay && tick > 0){
        // tncIterStats.printTAZForVehicles(idleVehicles.map(x=>x._2).toVector)
        //}

        //tncIterationStats.printMap()

        if (tick > 0 && maxNumberOfVehiclesToReposition <= 0) {
          // ignoring tick 0, as no vehicles checked in at that time
          logger.error(
            "Using RepositioningLowWaitingTimes allocation Manager but percentageOfVehiclesToReposition results in 0 respositioning - use Default Manager if not repositioning needed"
          )
        }

        var vehiclesToReposition =
          filterOutAlreadyRepositioningVehiclesIfEnoughAlternativeIdleVehiclesAvailable(
            idleVehicles,
            minimumNumberOfIdlingVehiclesThresholdForRepositioning
          )

        vehiclesToReposition = tncIterStats.getVehiclesCloseToIdlingAreas(
          vehiclesToReposition,
          maxNumberOfVehiclesToReposition,
          tick,
          timeWindowSizeInSecForDecidingAboutRepositioning,
          minimumNumberOfIdlingVehiclesThresholdForRepositioning,
          rideHailManager.beamServices
        )

        repositionCircleRadiusInMeters = tncIterStats.getUpdatedCircleSize(
          vehiclesToReposition,
          repositionCircleRadiusInMeters,
          tick,
          timeWindowSizeInSecForDecidingAboutRepositioning,
          minDemandPercentageInRadius,
          allowIncreasingRadiusIfDemandInRadiusLow
        )

        //val whichTAZToRepositionTo: Vector[(Id[Vehicle], Location)] = if (repositioningMethod.equalsIgnoreCase("basedOnWaitingTimeGravity")){
        // tncIterStats.repositionToBasedOnWaitingTimesGravity(vehiclesToReposition, repositionCircleRadiusInMeters, tick, timeWindowSizeInSecForDecidingAboutRepositioning, rideHailManager.beamServices)

        // TAZ1 -> waitingTime

        //} else {

        // define max TAZ to consider -keep to 10

        // keep same number as vehicles

        // add keepMaxTopNScores (TODO)

        val whichTAZToRepositionTo: Vector[(Id[BeamVehicle], Location)] =
          tncIterStats.reposition(
            vehiclesToReposition,
            repositionCircleRadiusInMeters,
            tick,
            timeWindowSizeInSecForDecidingAboutRepositioning,
            rideHailManager.beamServices
          )
        //}

        val produceDebugImages = repositioningConfig.produceDebugImages
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

            // for (vehToRepso <- rideHailManager.getIdleVehicles.values) {
            // spatialPlot.addPoint(PointToPlot(rideHailManager.getRideHailAgentLocation(vehToRepso.vehicleId).currentLocation.loc, Color.GREEN, 10))
            // }

            val tazEntries = tncIterStats getCoordinatesWithRideHailStatsEntry (tick, tick + 3600)

            for (tazEntry <- tazEntries.filter(x => x._2.getDemandEstimate > 0)) {
              if (firstRepositionCoordsOfDay.isEmpty || (firstRepositionCoordsOfDay.isDefined && rideHailManager.beamServices.geo
                    .distUTMInMeters(firstRepositionCoordsOfDay.get._1, tazEntry._1) < 10000)) {
                spatialPlot.addPoint(PointToPlot(tazEntry._1, Color.RED, 10))
                spatialPlot.addString(
                  StringToPlot(
                    s"(${tazEntry._2.getDemandEstimate},${tazEntry._2.sumOfWaitingTimes})",
                    tazEntry._1,
                    Color.RED,
                    20
                  )
                )
              }
            }

            for (vehToRepso <- whichTAZToRepositionTo) {
              val lineToPlot = LineToPlot(
                rideHailManager.vehicleManager
                  .getRideHailAgentLocation(vehToRepso._1)
                  .currentLocationUTM
                  .loc,
                vehToRepso._2,
                Color.blue,
                3
              )
              spatialPlot.addLine(lineToPlot)

              //log.debug(s"spatialPlot.addLine:${lineToPlot.toString}")
              //spatialPlot.addPoint(PointToPlot(rideHailManager.getRideHailAgentLocation(vehToRepso._1).currentLocation.loc, Color.YELLOW, 10))
            }

            /*if (firstRepositionCoordOfDay.isDefined) {
              spatialPlot.addString(StringToPlot("A", firstRepositionCoordOfDay.get, Color.BLACK, 50))
              spatialPlot.addString(StringToPlot("A", new Coord((spatialPlot.getBoundsCalculator().minX+spatialPlot.getBoundsCalculator().maxX)*4/10, (spatialPlot.getBoundsCalculator().minY+spatialPlot.getBoundsCalculator().maxY)*4/10), Color.BLACK, 50))
              spatialPlot.addString(StringToPlot("B", new Coord((spatialPlot.getBoundsCalculator().minX+spatialPlot.getBoundsCalculator().maxX)*6/10, (spatialPlot.getBoundsCalculator().minY+spatialPlot.getBoundsCalculator().maxY)*6/10), Color.BLACK, 50))
            } else {
              spatialPlot.addString(StringToPlot("A", firstRepositionCoordOfDay.get, Color.BLACK, 50))
            }*/

            if (firstRepositionCoordsOfDay.isEmpty) {
              firstRepositionCoordsOfDay = Some(
                rideHailManager.vehicleManager
                  .getRideHailAgentLocation(whichTAZToRepositionTo.head._1)
                  .currentLocationUTM
                  .loc,
                whichTAZToRepositionTo.head._2
              )
            }

            spatialPlot.addString(
              StringToPlot("A", firstRepositionCoordsOfDay.get._1, Color.BLACK, 50)
            )
            //spatialPlot.addString(StringToPlot("B", firstRepositionCoordsOfDay.get._2, Color.BLACK, 50))

            val iteration = "it." + rideHailManager.beamServices.matsimServices.getIterationNumber
            if (rideHailManager.beamServices.matsimServices != null)
              spatialPlot.writeImage(
                rideHailManager.beamServices.matsimServices.getControlerIO
                  .getIterationFilename(
                    rideHailManager.beamServices.matsimServices.getIterationNumber,
                    (tick / 3600 * 100).toInt / 100.0 + "locationOfAgentsInitally.png"
                  )
                  .replace(iteration, iteration + "/rideHailDebugging")
              )

            //if (!boundsCalculator.isDefined) {
            //  boundsCalculator = Some(spatialPlot.getBoundsCalculator())
            //}

          }
        }

        if (whichTAZToRepositionTo.nonEmpty) {
          logger.debug("whichTAZToRepositionTo.size:{}", whichTAZToRepositionTo.size)
        }

        val result = if (firstRepositioningOfDay) {
          firstRepositioningOfDay = false
          idleVehicles
            .map(idle => (idle._1, idle._2.currentLocationUTM.loc))
            .toVector
        } else {
          whichTAZToRepositionTo
        }

        result
      case None =>
        // iteration 0

        val idleVehiclesWithoutExcluded = rideHailManager.vehicleManager.getIdleVehiclesAndFilterOutExluded

        if (firstRepositioningOfDay && idleVehiclesWithoutExcluded.nonEmpty) {
          // these are zero distance repositionings
          // => this is a hack, as the tnc iteration stats does not know the initial position of any rideHailVehicle unless it has at least one pathTraversal during the day
          // this is needed to account for idling vehicles by TAZ, even if they are not moving during the whole day
          firstRepositioningOfDay = false

          //val mTazTreeMap = Try(TAZTreeMap.fromCsv(rideHailManager.beamServices.beamConfig.beam.agentsim.taz.file)).toOption

          //  val vehicleToTAZ=idleVehicles.foreach( x=> log.debug(s"${x._2.vehicleId} -> ${mTazTreeMap.get.getTAZ(x._2.currentLocation.loc.getX,
          //    x._2.currentLocation.loc.getY).tazId} -> ${x._2.currentLocation.loc}"))

          val result = idleVehiclesWithoutExcluded
            .map(idle => (idle._1, idle._2.currentLocationUTM.loc))
            .toVector
          result
        } else {
          Vector()
        }
    }
    // if (rideHailManager.getIdleVehicles().size >= 2) {
    // val origin=rideHailManager.getIdleVehicles().values.toVector
    //  val destination=scala.util.Random.shuffle(origin)
    // (for ((o,d)<-(origin zip destination)) yield (o.vehicleId,d.currentLocation.loc)) //.splitAt(4)._1
    // } else {
    // Vector()
    // }
  }

  def filterOutAlreadyRepositioningVehiclesIfEnoughAlternativeIdleVehiclesAvailable(
    idleVehicles: scala.collection.Map[Id[BeamVehicle], RideHailAgentLocation],
    newMaxNumberOfVehiclesToReposition: Int
  ): Vector[RideHailAgentLocation] = {
    val (idle, repositioning) = idleVehicles.values.toVector.partition(
      rideHailAgentLocation =>
        rideHailManager.modifyPassengerScheduleManager
          .isVehicleNeitherRepositioningNorProcessingReservation(rideHailAgentLocation.vehicleId)
    )
    val result = if (idle.size < newMaxNumberOfVehiclesToReposition) {
      idle ++ repositioning.take(newMaxNumberOfVehiclesToReposition - idle.size)
    } else {
      idle
    }

    if (result.size < idleVehicles.values.size) {
      logger.debug(
        "filterOutAlreadyRepositioningVehiclesIfEnoughAlternativeIdleVehiclesAvailable: reduced set by {}",
        idleVehicles.values.size - result.size
      )
    }

    result
  }
}
