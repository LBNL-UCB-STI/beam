package beam.agentsim.agents.ridehail.allocation

import java.io.{File, FileWriter}

import beam.agentsim.agents.ridehail.RideHailManager
import beam.agentsim.agents.ridehail.RideHailVehicleManager.RideHailAgentLocation
import beam.agentsim.agents.ridehail.repositioningmanager.DemandFollowingRepositioningManager
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.analysis.plots.GraphsStatsAgentSimEventsListener
import beam.router.BeamRouter.Location
import beam.sim.RideHailState
import beam.utils._
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.supercsv.io.CsvMapWriter
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

object RandomRepositioning {
  val QUAD_OUTPUT_FILE = "quad_output.csv"
  val COORD_OUTPUT_FILE = "coord_output.csv"
}

// Leaving this class here just for the historical purpose to show what we tried. Currently it is not used.
class RandomRepositioning(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager(rideHailManager)
    with LazyLogging {

  val rand: Random = new Random(rideHailManager.beamScenario.beamConfig.matsim.modules.global.randomSeed)

  val avgFreeSpeed: Double = {
    val freeSpeeds = rideHailManager.beamServices.networkHelper.allLinks.map(_.getFreespeed).sorted
    val freeSpeedInfo: String =
      s"""Free speed stats:
        |min: ${freeSpeeds.min}"
        |max: ${freeSpeeds.max}"
        |median: ${freeSpeeds(freeSpeeds.length / 2)}"
        |avg: ${freeSpeeds.sum / freeSpeeds.length}
      """.stripMargin
    logger.info(freeSpeedInfo)
    freeSpeeds.sum / freeSpeeds.length
  }

  val repoShare: Double = 0.02
  val numVehiclesToReposition: Int = (repoShare * rideHailManager.numRideHailAgents).toInt
  logger.info(
    s"repositioningShare: $repoShare, numRideHailAgents: ${rideHailManager.numRideHailAgents}, numVehiclesToReposition $numVehiclesToReposition"
  )

  val algorithm = 1

  // Algorithm #6 needs the state of ride-hail vehicles from previous iteration. It was stored in `RideHailState` and accessed from BeamServices, but it is removed now
  // If you want to make it work, bring that change back :)
  val rhs = new RideHailState // This should be replaces with actual data from prev iteration

  val vehicleAllowedToReposition: mutable.Set[Id[BeamVehicle]] = {
    mutable.HashSet(
      (rhs.getRideHailUtilization.notMovedAtAll ++ rhs.getRideHailUtilization.movedWithoutPassenger).toSeq: _*
    )
  }

  // Precompute on the first tick where to reposition for the whole day
  val lastTickWithRepos = 24 * 3600

  val step = 300
  val numberOfRepos = lastTickWithRepos / step
  var repositionPerTick = vehicleAllowedToReposition.size.toDouble / numberOfRepos
  repositionPerTick = if (repositionPerTick < 1) 1 else repositionPerTick
  logger.info(s"""
       |algorithm: ${algorithm}
       |vehicleAllowedToReposition: ${vehicleAllowedToReposition.size}
       |lastTickWithRepos: $lastTickWithRepos
       |step: $step
       |numberOfRepos: $numberOfRepos
       |repositionPerTick: $repositionPerTick""".stripMargin)

  val intervalSize: Int = step

  val activitySegment: ActivitySegment =
    ActivitySegment(rideHailManager.beamServices.matsimServices.getScenario, intervalSize)

  val algo8 = ProfilingUtils.timed("Initialized Algo8", x => logger.info(x)) {
    new DemandFollowingRepositioningManager(rideHailManager.beamServices, rideHailManager)
  }

  val intervalForUpdatingQuadTree = 1800

  var lastTimeQuadTreeUpdated = Double.NegativeInfinity

  var quadTree: QuadTree[Activity] = _

  def updatePersonActivityQuadTree(tick: Double): Unit = {
    val isOn: Boolean = true
    if (isOn && lastTimeQuadTreeUpdated + intervalForUpdatingQuadTree < tick) {
      // TODO: give preference to non repositioning vehicles -> filter them out!
      val currentTime = tick

      var minX: Double = Double.MaxValue
      var maxX: Double = Double.MinValue
      var minY: Double = Double.MaxValue
      var maxY: Double = Double.MinValue

      // TODO: optimize performance by not creating each time again!!! e.g. renew quadtree hourly

      val selectedActivities: ArrayBuffer[Activity] = ArrayBuffer[Activity]()

      rideHailManager.beamServices.matsimServices.getScenario.getPopulation.getPersons
        .values()
        .asScala
        .toList
        .flatMap(person => person.getSelectedPlan.getPlanElements.asScala)
        .foreach { planElement =>
          if (planElement.isInstanceOf[Activity]) {
            val act = planElement.asInstanceOf[Activity]
            if (act.getEndTime > currentTime + 20 * 60 && act.getEndTime < currentTime + 3600) {
              minX = Math.min(minX, act.getCoord.getX)
              minY = Math.min(minY, act.getCoord.getY)
              maxX = Math.max(maxX, act.getCoord.getX)
              maxY = Math.max(maxY, act.getCoord.getY)
              selectedActivities += act
            }

          }

        }

      quadTree = new QuadTree[Activity](minX, minY, maxX, maxY)

      selectedActivities.foreach { act =>
        quadTree.put(act.getCoord.getX, act.getCoord.getY, act)
      }
      lastTimeQuadTreeUpdated = tick
    }
  }

  def writeRepositioningToCSV(repositioningVehicles: Vector[(Id[BeamVehicle], Coord)], tick: Double) = {
    // TODO: write in the output folder graph

    // draw all content in quadTree with color blue

    val quad = quadTree.values().asScala.map { activity =>
      val coord = activity.getCoord
      Map(
        "time"     -> tick.toString,
        "x"        -> coord.getX.toString,
        "y"        -> coord.getY.toString,
        "activity" -> activity.getType
      )

    }

    // draw all repositioningVehicles._1 at rideHailManager.vehicleManager.getIdleVehicles in green

    // draw all repositioningVehicles._2 in blue (make arrow from green to blue)

    val vehicleSet = rideHailManager.vehicleManager.getIdleVehiclesAndFilterOutExluded
    val coord = repositioningVehicles.map { vehicleIdCoord =>
      val rideHailVehicleLocation = vehicleSet.get(vehicleIdCoord._1)
      val (x, y) = rideHailVehicleLocation match {
        case Some(rideHailLocation) =>
          (rideHailLocation.currentLocationUTM.loc.getX, rideHailLocation.currentLocationUTM.loc.getY)
        case None => (0, 0)
      }
      Map(
        "time" -> tick.toString,
        "x1"   -> x.toString,
        "y1"   -> y.toString,
        "x2"   -> vehicleIdCoord._2.getX.toString,
        "y2"   -> vehicleIdCoord._2.getY.toString
      )
    }

    val iterationNumber = rideHailManager.beamServices.matsimServices.getIterationNumber
    val quadFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO
      .getIterationFilename(iterationNumber, RandomRepositioning.QUAD_OUTPUT_FILE)
    val coordFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO
      .getIterationFilename(iterationNumber, RandomRepositioning.COORD_OUTPUT_FILE)

    writeCSV(quadFileName, Seq("time", "x", "y", "activity"), quad)
    writeCSV(coordFileName, Seq("time", "x1", "y1", "x2", "y2"), coord)

  }

  // Only override proposeVehicleAllocation if you wish to do something different from closest euclidean vehicle
  //  override def proposeVehicleAllocation(vehicleAllocationRequest: VehicleAllocationRequest): VehicleAllocationResponse

  // Map from tick to the pair of vehicleId (who to reposition) and location (where).
  var tickToLocation: Map[Int, Vector[(Id[BeamVehicle], Location)]] = Map.empty

  override def repositionVehicles(
    idleVehicles: scala.collection.Map[Id[BeamVehicle], RideHailAgentLocation],
    tick: Int
  ): Vector[(Id[BeamVehicle], Location)] = {

//    VehicleShouldRefuel(Id,Option[RefuelingLocation]) // None -> signal the human to refuel

    // Do tests: 1.) no repos 2.) with just upcomming next activities 3.) clustering, etc.

    ProfilingUtils.timed("updatePersonActivityQuadTree", x => logger.debug(x)) {
      updatePersonActivityQuadTree(tick)
    }

    algorithm match {
      case 8 =>
        algo8.repositionVehicles(Map.empty, tick)
      // TODO: destinations of idle vehicles selected for repositioning should be uniformally distributed in activity space

      // This should perform the same as DEFAULT_MANAGER!!!
      case 0 =>
        val nonRepositioningIdleVehicles =
          rideHailManager.vehicleManager.getIdleVehiclesAndFilterOutExluded.values.filter { ral =>
            rideHailManager.modifyPassengerScheduleManager.isVehicleNeitherRepositioningNorProcessingReservation(
              ral.vehicleId
            )
          }
        if (nonRepositioningIdleVehicles.size >= 2) {
          val result = rand
            .shuffle(nonRepositioningIdleVehicles)
            .splitAt(numVehiclesToReposition)
            ._1
            .map(x => (x.vehicleId, x.currentLocationUTM.loc))
            .toVector
          result
        } else {
          Vector.empty
        }

      case 1 =>
        val fleetSize = rideHailManager.fleetSize
        val numVehiclesToReposition = (repoShare * fleetSize).toInt

        // Get idle vehicles
        val idleVehicles = rideHailManager.vehicleManager.getIdleVehiclesAndFilterOutExluded.values
        // Shuffle only once and split it by `numVehiclesToReposition`

        // max reposition diameter: 5000m

        //corxXIdleVehicle + diameter * (rand.nextDouble() - 0.5)
        // check if dest within boudning box of map ->

        val numRepos = if (numVehiclesToReposition * 2 >= idleVehicles.size) {
          idleVehicles.size / 2
        } else {
          numVehiclesToReposition
        }

        val (src, dst) = rand.shuffle(idleVehicles).splitAt(numRepos)

        // e.g. do: RideHailManager.INITIAL_RIDE_HAIL_LOCATION_UNIFORM_RANDOM

        // Get the source vehicles by taking `numVehiclesToReposition` vehicles
        val srcLocations = src.take(numRepos)

        // Get the destination
        // Make sure we exclude `srcLocations`
        val dstLocations = dst.take(numVehiclesToReposition)

        val result = srcLocations.zip(dstLocations).map {
          case (s, d) =>
            (s.vehicleId, d.currentLocationUTM.loc)
        }
        result.toVector

      //        if (rideHailManager.vehicleManager.getIdleVehicles.size >= 2) {
      //          // TODO: shuffle origin as well -> otherwise same vehicles maybe shuffled!!!!!!!!!! -> see next case
      //          val origin = rideHailManager.vehicleManager.getIdleVehicles.values.toVector
      //          val destination = scala.util.Random.shuffle(origin)
      //          (for ((o, d) <- origin zip destination)
      //            yield (o.vehicleId, d.currentLocationUTM.loc))
      //            .splitAt(numVehiclesToReposition)
      //            ._1
      //        } else {
      //          Vector()
      //        }

      case 2 =>
        // max distance travel is 20min
        // TODO: use skims to derive radius from it or other way around.

        val nonRepositioningIdleVehicles =
          rideHailManager.vehicleManager.getIdleVehiclesAndFilterOutExluded.values.filter { ral =>
            rideHailManager.modifyPassengerScheduleManager.isVehicleNeitherRepositioningNorProcessingReservation(
              ral.vehicleId
            )
          }
        if (nonRepositioningIdleVehicles.size >= 2) {
          val activitiesCoordinates = activitySegment.getActivities(tick + 20 * 60, tick + 3600).map(_.getCoord)
          val vehiclesToReposition = nonRepositioningIdleVehicles.par.flatMap { vehIdAndLoc =>
            val vehicleId = vehIdAndLoc.vehicleId
            val location = vehIdAndLoc.currentLocationUTM
            val nearBy = activitiesCoordinates.filter { actCoord =>
              val distance = rideHailManager.beamServices.geo.distUTMInMeters(actCoord, location.loc)
              distance <= 5000 // distance <= 5000 && distance >= 300
            }
            val shuffled = rand.shuffle(nearBy)
            shuffled.headOption.map { coord =>
              (vehicleId, coord)
            }
          }.seq
          val result = rand
            .shuffle(vehiclesToReposition)
            .splitAt(numVehiclesToReposition)
            ._1
            .toVector
          val percent = (if (nonRepositioningIdleVehicles.isEmpty) 0
                         else result.size.toDouble / nonRepositioningIdleVehicles.size) * 100
          logger.info(
            s"Will reposition ${result.size} which are randomly picked from ${vehiclesToReposition.size}. Number of nonRepositioningIdleVehicles: ${nonRepositioningIdleVehicles.size}, repositioning ${percent} % of idle vehicle"
          )
          logger.info(s"tick is $tick. Activities in [tick + 20*60, tick + 3600]: ${activitiesCoordinates.size}")
          showDistanceStats(result)

          logger.whenDebugEnabled {
            result.foreach {
              case (id, coord) =>
                val vehLoc = rideHailManager.vehicleManager.getRideHailAgentLocation(id).currentLocationUTM.loc
                val distance = rideHailManager.beamServices.geo.distUTMInMeters(coord, vehLoc)
                logger.debug(s"$tick: Going to reposition $id to $coord which is $distance m away")
            }
          }

          // writeRepositioningToCSV(result, tick)

          result
        } else {
          Vector()
        }
      // Same as 3, but with constrain `distance <= 40000 && distance >= 10000`
      case 4 =>
        // max distance travel is 20min
        // TODO: use skims to derive radius from it or other way around.

        val nonRepositioningIdleVehicles =
          rideHailManager.vehicleManager.getIdleVehiclesAndFilterOutExluded.values.filter { ral =>
            rideHailManager.modifyPassengerScheduleManager.isVehicleNeitherRepositioningNorProcessingReservation(
              ral.vehicleId
            )
          }
        if (nonRepositioningIdleVehicles.size >= 2) {
          val activitiesCoordinates = activitySegment.getActivities(tick + 20 * 60, tick + 3600).map(_.getCoord)
          val vehiclesToReposition = nonRepositioningIdleVehicles.par.flatMap { vehIdAndLoc =>
            val vehicleId = vehIdAndLoc.vehicleId
            val location = vehIdAndLoc.currentLocationUTM
            val filtered = activitiesCoordinates.filter { actCoord =>
              val distance = rideHailManager.beamServices.geo.distUTMInMeters(actCoord, location.loc)
              distance <= 40000 && distance >= 10000
            }
            val maybeFurthest =
              if (filtered.isEmpty)
                None
              else {
                Some(filtered.maxBy { actCoord =>
                  rideHailManager.beamServices.geo.distUTMInMeters(actCoord, location.loc)
                })
              }
            maybeFurthest.map { coord =>
              (vehicleId, coord)
            }
          }.seq
          val result = rand
            .shuffle(vehiclesToReposition)
            .splitAt(numVehiclesToReposition)
            ._1
            .toVector
          val percent = (if (nonRepositioningIdleVehicles.isEmpty) 0
                         else result.size.toDouble / nonRepositioningIdleVehicles.size) * 100
          logger.info(
            s"Will reposition ${result.size} which are randomly picked from ${vehiclesToReposition.size}. Number of nonRepositioningIdleVehicles: ${nonRepositioningIdleVehicles.size}, repositioning ${percent} % of idle vehicle"
          )
          logger.info(s"tick is $tick. Activities in [tick + 20*60, tick + 3600]: ${activitiesCoordinates.size}")
          showDistanceStats(result)

          logger.whenDebugEnabled {
            result.foreach {
              case (id, coord) =>
                val vehLoc = rideHailManager.vehicleManager.getRideHailAgentLocation(id).currentLocationUTM.loc
                val distance = rideHailManager.beamServices.geo.distUTMInMeters(coord, vehLoc)
                logger.debug(s"$tick: Going to reposition $id to $coord which is $distance m away")
            }
          }

          // writeRepositioningToCSV(result, tick)

          result
        } else {
          Vector()
        }

      case 3 =>
        // max distance travel is 20min
        // TODO: use skims to derive radius from it or other way around.
        val fleetSize = rideHailManager.fleetSize
        val numVehiclesToReposition = (repoShare * fleetSize).toInt
        val vehicleSet = rideHailManager.vehicleManager.getIdleVehiclesAndFilterOutExluded
        if (vehicleSet.size >= 2) {
          val nonRepositioningIdleVehicles = vehicleSet.values.filter { ral =>
            rideHailManager.modifyPassengerScheduleManager.isVehicleNeitherRepositioningNorProcessingReservation(
              ral.vehicleId
            )
          }

          // Getting the furthest vehicle from Activity: vehicle which has nothing to do
          // Find TOP 2 * numVehiclesToReposition furthest vehicle, shuffle them and get only `numVehiclesToReposition` to reposition
          val vehiclesToReposition = rand
            .shuffle(
              nonRepositioningIdleVehicles
                .flatMap { vehLocation =>
                  val loc = vehLocation.currentLocationUTM.loc
                  Option(quadTree.getClosest(loc.getX, loc.getY)).map { act =>
                    val distance = rideHailManager.beamServices.geo.distUTMInMeters(act.getCoord, loc)
                    (vehLocation, distance)
                  }
                }
                .toVector
                .sortBy { case (vehLocation, distance) => -distance }
                .map(_._1)
                .splitAt(2 * numVehiclesToReposition)
                ._1
            )
            .splitAt(numVehiclesToReposition)
            ._1

          // We're trying to move idle furthest vehicle to the activities which have no vehicles close to
          // For each vehicle to reposition:
          // - We trying to find an activity by the current location of vehicle
          // - For that found activity, we try to find the closest available vehicle to that activity and measure the distance to it
          // - We create a pair (Activity, Distance)
          // Now we need to sort these pairs in desc order, so that's why `-distance` and get the head
          val result = vehiclesToReposition.par
            .map { vehIdAndLoc =>
              val vehicleId = vehIdAndLoc.vehicleId
              val location = vehIdAndLoc.currentLocationUTM

              val dest =
                quadTree
                  .getDisk(location.loc.getX, location.loc.getY, 5000)
                  .asScala
                  .toList
                  .map { act =>
                    // Get the closest available vehicle by activity coord
                    val closestIdleRHVehicle = rideHailManager.vehicleManager.idleRideHailAgentSpatialIndex
                      .getClosest(act.getCoord.getX, act.getCoord.getY)
                    // Measure the distance to vehicle
                    val distance = rideHailManager.beamServices.geo.distUTMInMeters(
                      act.getCoord,
                      closestIdleRHVehicle.currentLocationUTM.loc
                    )

                    (act, distance)
                  }
                  .sortBy { case (act, distance) => -distance }
                  .map { _._1 }
                  .headOption

              dest match {
                case Some(act) => (vehicleId, act.getCoord)
                case _         => (vehicleId, new Coord(Double.MaxValue, Double.MaxValue))
              }

            }
            .filterNot(_._2.getX == Double.MaxValue)
            .seq
            .toVector

          // writeRepositioningToCSV(result, tick)

          result
        } else {
          Vector()
        }

      case 6 =>
        if (tick == 0) {
          val okToReposition = rhs.getRideHailUtilization.notMovedAtAll ++ rhs.getRideHailUtilization.movedWithoutPassenger
          val neverMovedVehiclesBatched = rand
            .shuffle(okToReposition)
            .toVector
            .sliding(numberOfRepos, numberOfRepos)
            .toArray

          logger.info(s"okToReposition size: ${okToReposition.size}")
          logger.info(s"neverMovedVehiclesBatched size: ${neverMovedVehiclesBatched.length}")

          if (repositionPerTick >= 1) {
            var idx: Int = 0
            val map = (0 to lastTickWithRepos by step).map {
              case t =>
                val activities: Vector[Location] = rand
                  .shuffle(activitySegment.getActivities(tick + 20 * 60, tick + 3600))
                  .map(_.getCoord)
                  .take(numberOfRepos)
                  .toVector
                // Use `lift` to be in safe
                val ids = neverMovedVehiclesBatched.lift(idx).getOrElse(Vector.empty)
                logger.info(s"t: $t. VehicleIds: ${activities.size}, Activities locations: ${activities.size}")
                idx += 1
                t -> ids.zip(activities)
            }.toMap
            tickToLocation = map
          } else {
            logger.warn(s"repositionPerTick: $repositionPerTick. There will be logic to handle it :)")
          }
        }
        val vehicleSet = rideHailManager.vehicleManager.getIdleVehiclesAndFilterOutExluded
        tickToLocation.getOrElse(tick, Vector.empty).filter {
          case (vehicleId, _) =>
            rideHailManager.modifyPassengerScheduleManager
              .isVehicleNeitherRepositioningNorProcessingReservation(vehicleId) &&
            vehicleSet.contains(vehicleId)
        }
      // The same as 6 algo, but we will reposition
      case 7 =>
        val candidateToReposition = rideHailManager.vehicleManager.getIdleVehiclesAndFilterOutExluded.values.filter {
          ral =>
            rideHailManager.modifyPassengerScheduleManager.isVehicleNeitherRepositioningNorProcessingReservation(
              ral.vehicleId
            ) &&
            vehicleAllowedToReposition.contains(ral.vehicleId)
        }
        val toReposition = rand.shuffle(candidateToReposition).take(repositionPerTick.toInt).map(_.vehicleId)
        // Remove from allowed set
        vehicleAllowedToReposition --= toReposition

        val activities: Vector[Location] = rand
          .shuffle(activitySegment.getActivities(tick + 20 * 60, tick + 3600))
          .map(_.getCoord)
          .take(repositionPerTick.toInt)
          .toVector

        toReposition.zip(activities).toVector

    }
    // other algorithms: compute on TAZ level need for new Vehicle: AvailableIdleRidehailVehicles/endingActsInOneHour_orDifferentInterval
    //

    // also potential for improvement: don't select idle vehicles randomly, but select those which have no near future demand around them (e.g. within 3000m)
    // -> but there should be some demand
    //
    // places with no idle vehicle next to an ending activity is more attractive than if no already idle vehicles there
    // find out if oversupply at a location
    // TODO: just based on upcomming next activities

    // add radius for repositioning and radius increase if no activities?

    // choice of which vehicles to move: assess low demand areas based on activity end times as well!
    // TODO: add initial rh location algorithm which is based on activity clusters over the day
    // don't consider last activity as no leg after that

  }

  def showDistanceStats(result: Vector[(Id[BeamVehicle], Location)]): Unit = {
    val distances = result.map {
      case (id, coord) =>
        val vehLoc = rideHailManager.vehicleManager.getRideHailAgentLocation(id).currentLocationUTM.loc
        rideHailManager.beamServices.geo.distUTMInMeters(coord, vehLoc)
    }
    val stats = Statistics.apply(distances)
    logger.info(s"Repositining distance stat: $stats")
  }

  private def writeCSV(path: String, headers: Seq[String], rows: Iterable[Map[String, String]]): Unit = {
    val file = new File(path)
    val exist = file.exists()
    val fileWriter = new FileWriter(file, true)

    FileUtils.using(new CsvMapWriter(fileWriter, CsvPreference.STANDARD_PREFERENCE)) { writer =>
      if (!exist) {
        writer.writeHeader(headers: _*)
      }
      val headersArray = headers.toArray

      rows.foreach { row =>
        writer.write(row.asJava, headersArray: _*)
      }
    }
  }
}
