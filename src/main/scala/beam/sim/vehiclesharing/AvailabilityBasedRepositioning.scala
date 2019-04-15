package beam.sim.vehiclesharing
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import com.vividsolutions.jts.index.quadtree.Quadtree

import scala.collection.mutable
import scala.collection.JavaConverters._

private[vehiclesharing] class AvailabilityBasedRepositioning(
  skimmer: BeamSkimmer,
  beamServices: BeamServices,
  vehicles: Quadtree
) {

  case class RepositioningRequest(taz: TAZ, availableVehicles: Int, shortage: Int)

  val oversuppliedTAZ =
    mutable.TreeSet.empty[RepositioningRequest](Ordering.by[RepositioningRequest, Int](_.availableVehicles))
  val undersuppliedTAZ = mutable.TreeSet.empty[RepositioningRequest](Ordering.by[RepositioningRequest, Int](_.shortage))

  def getVehiclesForReposition(startTime: Int, endTime: Int): List[VehiclesForReposition] = {
    beamServices.tazTreeMap.getTAZs.foreach { taz =>
      val availability =
        skimmer.getSkimPlusValues(startTime, endTime, taz.tazId, "default").foldLeft((0.0, 1, Int.MaxValue)) {
          case ((avg, idx, minAV), next) =>
            (avg + (next.demand - avg) / idx, idx + 1, Math.min(minAV, next.availableVehicles))
        }
      if (availability._3 > 0 && availability._3 < Int.MaxValue) {
        oversuppliedTAZ.add(RepositioningRequest(taz, availability._3, 0))
      } else if (availability._3 == 0) {
        undersuppliedTAZ.add(RepositioningRequest(taz, 0, 1))
      }
      println(
        s"reposition tick ======> $startTime | minAvailability: ${availability._3} | avgDemand: ${availability._1}"
      )
    }

    val vehiclesForReposition = new mutable.ListBuffer[VehiclesForReposition]()
    oversuppliedTAZ.foreach { os =>
      var destinationOption: Option[(RepositioningRequest, Double)] = None
      undersuppliedTAZ.foreach { us =>
        destinationOption = skimmer.getSkimValue(0, BeamMode.CAR, os.taz.tazId, us.taz.tazId).map {
          case skim if destinationOption.isDefined && skim.time < destinationOption.get._2 => (us, skim.time)
          case skim if destinationOption.isEmpty                                           => (us, skim.time)
        }
      }
      destinationOption match {
        case Some(destination) =>
          undersuppliedTAZ.remove(destination._1)
          val arrivalTime = startTime + destination._2.round.toInt
          val vehiclesInTAZ = vehicles
            .queryAll()
            .asScala
            .filter(
              v =>
                destination._1.taz == beamServices.tazTreeMap.getTAZ(
                  v.asInstanceOf[BeamVehicle].spaceTime.loc.getX,
                  v.asInstanceOf[BeamVehicle].spaceTime.loc.getY
              )
            )
          vehiclesInTAZ
            .take(1)
            .foreach(
              v =>
                vehiclesForReposition.prepend(
                  VehiclesForReposition(
                    v.asInstanceOf[BeamVehicle],
                    new SpaceTime(destination._1.taz.coord, arrivalTime)
                  )
              )
            )
        case None =>
      }
    }
    vehiclesForReposition.toList
  }

}
