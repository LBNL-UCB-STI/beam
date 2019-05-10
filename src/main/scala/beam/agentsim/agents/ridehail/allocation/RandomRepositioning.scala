package beam.agentsim.agents.ridehail.allocation

import java.io.FileWriter
import java.io.File

import beam.agentsim.agents.ridehail.RideHailManager
import beam.analysis.plots.GraphsStatsAgentSimEventsListener
import beam.router.BeamRouter.Location
import beam.utils.FileUtils
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.population.{Activity, Person, PlanElement}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.vehicles.Vehicle
import org.supercsv.io.CsvMapWriter
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._

class RandomRepositioning(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager(rideHailManager){

  val intervalForUpdatingQuadTree=1800

  var lastTimeQuadTreeUpdated=Double.NegativeInfinity

  var quadTree:QuadTree[Activity]=_

  def updatePersonActivityQuadTree(tick: Double) = {
   // rideHailManager.beamServices.matsimServices.getScenario.getPopulation.getPersons.values().stream().forEach{ person =>
  //    person.getSelectedPlan
//
  //  }

  if (lastTimeQuadTreeUpdated+intervalForUpdatingQuadTree<tick){

    // TODO: give preference to non repositioning vehicles -> filter them out!

    val currentTime=tick

    var minX: Double = Double.MaxValue
    var maxX: Double = Double.MinValue
    var minY: Double = Double.MaxValue
    var maxY: Double = Double.MinValue

    // TODO: optimize performance by not creating each time again!!! e.g. renew quadtree hourly

    var selectedActivities:List[Activity]=List[Activity]()

    rideHailManager.beamServices.matsimServices.getScenario.getPopulation.getPersons.values().asScala.toList.flatMap( person => person.getSelectedPlan.getPlanElements.asScala).foreach{
      planElement =>

      if (planElement.isInstanceOf[Activity]){
        val act=planElement.asInstanceOf[Activity]
        if (act.getEndTime>currentTime +20*60 && act.getEndTime<currentTime +3600){
          minX = Math.min(minX, act.getCoord.getX)
          minY = Math.min(minY, act.getCoord.getY)
          maxX = Math.max(maxX, act.getCoord.getX)
          maxY = Math.max(maxY, act.getCoord.getY)

          selectedActivities=selectedActivities :+ act

        }

      }

    }

    quadTree=new QuadTree[Activity](minX,minY,maxX,maxY)

    selectedActivities.foreach{ act =>  quadTree.put(act.getCoord.getX,act.getCoord.getY,act)}
    }
  }



  def writeRepositioningToCSV(repositioningVehicles:  Vector[(Id[Vehicle], Coord)],tick: Double) = {
    // TODO: write in the output folder graph

    // draw all content in quadTree with color blue

    val quad = quadTree.values().asScala.map{activity =>
      val coord = activity.getCoord
      Map("time" -> tick.toString, "x"-> coord.getX.toString, "y" -> coord.getY.toString, "activity" -> activity.getType)

    }

    // draw all repositioningVehicles._1 at rideHailManager.vehicleManager.getIdleVehicles in green

    // draw all repositioningVehicles._2 in blue (make arrow from green to blue)


    val coord = repositioningVehicles.map{ vehicleIdCoord =>
        val rideHailVehicleLocation = rideHailManager.vehicleManager.getIdleVehicles.get(vehicleIdCoord._1)
        val (x,y) = rideHailVehicleLocation match {
          case Some(rideHailLocation) => (rideHailLocation.currentLocationUTM.loc.getX,rideHailLocation.currentLocationUTM.loc.getY)
          case None => (0,0)
        }
        Map("time" -> tick.toString, "x1"-> x.toString, "y1" -> y.toString,"x2"-> vehicleIdCoord._2.getX.toString, "y2" -> vehicleIdCoord._2.getY.toString)
    }

    val iterationNumber = rideHailManager.beamServices.iterationNumber
    val quadFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, "quad_output.csv")
    val coordFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, "coord_output.csv")

    writeCSV(quadFileName, Seq("time","x","y","activity"), quad)
    writeCSV(coordFileName, Seq("time","x1","y1","x2","y2"), coord)


  }



  // Only override proposeVehicleAllocation if you wish to do something different from closest euclidean vehicle
  //  override def proposeVehicleAllocation(vehicleAllocationRequest: VehicleAllocationRequest): VehicleAllocationResponse

  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = {

    // Do tests: 1.) no repos 2.) with just upcomming next activities 3.) clustering, etc.

    updatePersonActivityQuadTree(tick)





    val algorithm=2

    algorithm match {
      case 1 =>
        val repositioningShare =
          rideHailManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.randomRepositioning.repositioningShare
        val fleetSize = rideHailManager.fleetSize
        val numVehiclesToReposition = (repositioningShare * fleetSize).toInt
        if (rideHailManager.vehicleManager.getIdleVehicles.size >= 2) {
          // TODO: shuffle origin as well -> otherwise same vehicles maybe shuffled!!!!!!!!!! -> see next case
          val origin = rideHailManager.vehicleManager.getIdleVehicles.values.toVector
          val destination = scala.util.Random.shuffle(origin)
          (for ((o, d) <- origin zip destination)
            yield (o.vehicleId, d.currentLocationUTM.loc))
            .splitAt(numVehiclesToReposition)
            ._1
        } else {
          Vector()
        }
      case 2 =>
        val repositioningShare =
          rideHailManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.randomRepositioning.repositioningShare
        val fleetSize = rideHailManager.fleetSize
        val numVehiclesToReposition = (repositioningShare * fleetSize).toInt
        if (rideHailManager.vehicleManager.getIdleVehicles.size >= 2) {

          val allVehicles=rideHailManager.vehicleManager.getIdleVehicles.toList

          val vehiclesToReposition=scala.util.Random.shuffle(allVehicles).splitAt(numVehiclesToReposition)._1

          val result=vehiclesToReposition.map{ vehIdAndLoc =>
            val (vehicleId,location) = vehIdAndLoc

            val dest=scala.util.Random.shuffle(quadTree.getDisk(location.currentLocationUTM.loc.getX,location.currentLocationUTM.loc.getY,5000).asScala.toList).headOption

            dest match {
              case Some(act) => (vehicleId,act.getCoord)
              case _ => (vehicleId,new Coord(Double.MaxValue,Double.MaxValue))
            }

          }.toVector.filterNot(_._2.getX==Double.MaxValue)


          writeRepositioningToCSV(result,tick)

          result
        } else {
          Vector()
        }

    }


    // TODO: just based on upcomming next activities

    // add radius for repositioning and radius increase if no activities?

    // choice of which vehicles to move: assess low demand areas based on activity end times as well!

  }


  private def writeCSV(path: String, headers: Seq[String], rows: Iterable[Map[String, String]]): Unit = {
    val file = new File(path)
    val exist = file.exists()
    val fileWriter = new FileWriter(file, true)

    FileUtils.using(new CsvMapWriter(fileWriter, CsvPreference.STANDARD_PREFERENCE)) { writer =>
      if(!exist){
        writer.writeHeader(headers: _*)
      }
      val headersArray = headers.toArray

      rows.foreach { row =>
        writer.write(row.asJava, headersArray: _*)
      }
    }
  }
}
