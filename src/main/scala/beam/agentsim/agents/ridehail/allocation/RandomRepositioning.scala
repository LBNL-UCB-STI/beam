package beam.agentsim.agents.ridehail.allocation

import java.io.{File, FileWriter}

import beam.agentsim.agents.ridehail.RideHailManager
import beam.analysis.plots.GraphsStatsAgentSimEventsListener
import beam.router.BeamRouter.Location
import beam.utils.{DebugLib, FileUtils, RandomUtils}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.vehicles.Vehicle
import org.supercsv.io.CsvMapWriter
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

object RandomRepositioning {
  val QUAD_OUTPUT_FILE = "quad_output.csv"
  val COORD_OUTPUT_FILE = "coord_output.csv"
}

class RandomRepositioning(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager(rideHailManager)
    with LazyLogging {
  if (rideHailManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionTimeoutInSeconds == 0) {
    logger.warn(
      "RandomRepositioning need to have set `beam.agentsim.agents.rideHail.allocationManager.repositionTimeoutInSeconds` > 0!"
    )
  }
  //  val intervalSize: Int = 300
  //
  //  val activities = rideHailManager.beamServices.matsimServices.getScenario.getPopulation.getPersons.values.asScala
  //    .flatMap { person =>
  //      person.getSelectedPlan.getPlanElements.asScala.collect {
  //        case act: Activity if act.getEndTime != Double.NegativeInfinity =>
  //          act
  //      }
  //    }
  //    .toArray
  //    .sortBy(x => x.getEndTime)
  //
  //  try {
  //    val as = ActivitySegment(rideHailManager.beamServices.matsimServices.getScenario, intervalSize)
  //
  //  } catch {
  //    case ex: Exception =>
  //      print(ex)
  //  }

  val intervalForUpdatingQuadTree = 1800

  var lastTimeQuadTreeUpdated = Double.NegativeInfinity

  var quadTree: QuadTree[Activity] = _

  def updatePersonActivityQuadTree(tick: Double) = {
    // rideHailManager.beamServices.matsimServices.getScenario.getPopulation.getPersons.values().stream().forEach{ person =>
    //    person.getSelectedPlan
    //
    //  }

    if (lastTimeQuadTreeUpdated + intervalForUpdatingQuadTree < tick) {

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
    }
  }

  def writeRepositioningToCSV(repositioningVehicles: Vector[(Id[Vehicle], Coord)], tick: Double) = {
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

    val coord = repositioningVehicles.map { vehicleIdCoord =>
      val rideHailVehicleLocation = rideHailManager.vehicleManager.getIdleVehicles.get(vehicleIdCoord._1)
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

  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = {

//    VehicleShouldRefuel(Id,Option[RefuelingLocation]) // None -> signal the human to refuel

    // Do tests: 1.) no repos 2.) with just upcomming next activities 3.) clustering, etc.

    updatePersonActivityQuadTree(tick)

    val algorithm = 2

    algorithm match {

      // TODO: destinations of idle vehicles selected for repositioning should be uniformally distributed in activity space

      case 1 =>
        val repositioningShare =
          rideHailManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.randomRepositioning.repositioningShare
        val fleetSize = rideHailManager.fleetSize
        val numVehiclesToReposition = (repositioningShare * fleetSize).toInt

        // Get idle vehicles
        val idleVehicles = rideHailManager.vehicleManager.getIdleVehicles.values
        // Shuffle only once and split it by `numVehiclesToReposition`

        // max reposition diameter: 5000m

        //corxXIdleVehicle + diameter * (rand.nextDouble() - 0.5)
        // check if dest within boudning box of map ->

        val numRepos = if (numVehiclesToReposition * 2 >= idleVehicles.size) {
          idleVehicles.size / 2
        } else {
          numVehiclesToReposition
        }

        val (src, dst) = RandomUtils.shuffle(idleVehicles, new java.util.Random()).splitAt(numRepos)

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

        val repositioningShare =
          rideHailManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.randomRepositioning.repositioningShare
        val fleetSize = rideHailManager.fleetSize
        val numVehiclesToReposition = (repositioningShare * fleetSize).toInt
        val idleVehicles = rideHailManager.vehicleManager.getIdleVehicles.values
        if (idleVehicles.size >= 2) {
          val vehiclesToReposition =
            RandomUtils.shuffle(idleVehicles, new java.util.Random()).splitAt(numVehiclesToReposition)._1

          logger.info(
            s"repositioningShare: $repositioningShare, fleetSize: $fleetSize, vehiclesToReposition size: ${vehiclesToReposition.size}"
          )

          val result = vehiclesToReposition.par
            .map { vehIdAndLoc =>
              val vehicleId = vehIdAndLoc.vehicleId
              val location = vehIdAndLoc.currentLocationUTM

              val dest = scala.util.Random
                .shuffle(
                  quadTree
                    .getDisk(location.loc.getX, location.loc.getY, 5000)
                    .asScala
                    .toList
                )
                .headOption

              dest match {
                case Some(act) => (vehicleId, act.getCoord)
                case _         => (vehicleId, new Coord(Double.MaxValue, Double.MaxValue))
              }

            }
            .filterNot(_._2.getX == Double.MaxValue)
            .seq
            .toVector

          result.foreach {
            case (id, coord) =>
              logger.debug(s"$tick: Going to reposition $id to $coord")
          }
          writeRepositioningToCSV(result, tick)

          result
        } else {
          Vector()
        }

      case 3 =>
        // max distance travel is 20min
        // TODO: use skims to derive radius from it or other way around.

        val repositioningShare =
          rideHailManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.randomRepositioning.repositioningShare
        val fleetSize = rideHailManager.fleetSize
        val numVehiclesToReposition = (repositioningShare * fleetSize).toInt
        if (rideHailManager.vehicleManager.getIdleVehicles.size >= 2) {

          val idleVehicles = rideHailManager.vehicleManager.getIdleVehicles.toList

          val vehiclesToReposition = scala.util.Random
            .shuffle(
              idleVehicles
                .map { vehLocation =>
                  val loc = vehLocation._2.currentLocationUTM.loc

                  val act = quadTree.getClosest(loc.getX, loc.getY)

                  if (act == null) {
                    DebugLib.emptyFunctionForSettingBreakPoint()
                  }

                  val distance = rideHailManager.beamServices.geo.distUTMInMeters(act.getCoord, loc)
                  (vehLocation, distance)
                }
                .sortBy { case (vehLocation, distance) => -distance }
                .map(_._1)
                .splitAt(2 * numVehiclesToReposition)
                ._1
            )
            .splitAt(numVehiclesToReposition)
            ._1

          val result = vehiclesToReposition
            .map { vehIdAndLoc =>
              val (vehicleId, location) = vehIdAndLoc

              val dest =
                quadTree
                  .getDisk(location.currentLocationUTM.loc.getX, location.currentLocationUTM.loc.getY, 5000)
                  .asScala
                  .toList
                  .map { act =>
                    val closestIdleRHVehicle = rideHailManager.vehicleManager.idleRideHailAgentSpatialIndex
                      .getClosest(act.getCoord.getX, act.getCoord.getY)

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
            .toVector
            .filterNot(_._2.getX == Double.MaxValue)

          // writeRepositioningToCSV(result, tick)

          result
        } else {
          Vector()
        }

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
