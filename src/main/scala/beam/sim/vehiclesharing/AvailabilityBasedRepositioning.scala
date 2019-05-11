package beam.sim.vehiclesharing
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode
import beam.sim.BeamServices

import org.matsim.api.core.v01.Id

import scala.collection.JavaConverters._
import scala.collection.mutable

private[vehiclesharing] class AvailabilityBasedRepositioning(
  beamSkimmer: BeamSkimmer,
  beamServices: BeamServices
) extends RepositionAlgorithm {

  case class RepositioningRequest(taz: TAZ, availableVehicles: Int, shortage: Int)

  val LABEL: String = "availability"

  override def getVehiclesForReposition(
    startTime: Int,
    endTime: Int,
    repositionManager: RepositionManager
  ): List[(BeamVehicle, SpaceTime, Id[TAZ])] = {
    val relocate = beamServices.iterationNumber > 0 || beamServices.beamConfig.beam.warmStart.enabled
    val oversuppliedTAZ =
      mutable.TreeSet.empty[RepositioningRequest](Ordering.by[RepositioningRequest, Int](_.availableVehicles))
    val undersuppliedTAZ =
      mutable.TreeSet.empty[RepositioningRequest](Ordering.by[RepositioningRequest, Int](_.shortage))

    beamServices.tazTreeMap.getTAZs.foreach { taz =>
      if (relocate) {
        val availabilityVect =
          beamSkimmer.getPreviousSkimPlusValues(startTime, endTime, taz.tazId, repositionManager.getId, LABEL)
        val availability = availabilityVect.drop(1).foldLeft(availabilityVect.headOption.getOrElse(0.0).toInt) {
          (minV, cur) =>
            Math.min(minV, cur.toInt)
        }
        val demandVect = beamSkimmer.getPreviousSkimPlusValues(
          startTime,
          endTime,
          taz.tazId,
          repositionManager.getId,
          repositionManager.getDemandLabel
        )
        val demand = demandVect
          .foldLeft((0.0, 0)) { case ((avgV, countV), cur) => ((avgV * countV + cur) / (cur + 1), countV + 1) }
          ._1

        if (availability > 0 && availability < Int.MaxValue) {
          oversuppliedTAZ.add(RepositioningRequest(taz, availability, 0))
        } else if (availability == 0) {
          undersuppliedTAZ.add(RepositioningRequest(taz, 0, 1))
        }
//        println(
//          s"reposition tick ======> $startTime | minAvailability: $availability | avgDemand: $demand"
//        )
      }
    }

    val vehiclesForReposition = new mutable.ListBuffer[(BeamVehicle, SpaceTime, Id[TAZ])]()
    oversuppliedTAZ.foreach { os =>
      var destinationOption: Option[(RepositioningRequest, Double)] = None
      undersuppliedTAZ.foreach { us =>
        val skim = beamSkimmer.getPreviousSkimValueOrDefault(startTime, BeamMode.CAR, os.taz.tazId, us.taz.tazId)
        destinationOption = destinationOption match {
          case Some(someDest) if skim.time < someDest._2  => Some((us, skim.time))
          case Some(someDest) if skim.time >= someDest._2 => destinationOption
          case None                                       => Some((us, skim.time))
        }
      }
      destinationOption match {
        case Some(destination) =>
          undersuppliedTAZ.remove(destination._1)
          val arrivalTime = startTime + destination._2.round.toInt
          val vehiclesInTAZ = repositionManager.getAvailableVehicles
            .queryAll()
            .asScala
            .filter(
              v =>
                destination._1.taz.tazId.toString == beamServices.tazTreeMap
                  .getTAZ(
                    v.asInstanceOf[BeamVehicle].spaceTime.loc.getX,
                    v.asInstanceOf[BeamVehicle].spaceTime.loc.getY
                  )
                  .tazId
                  .toString
            )
          vehiclesInTAZ
            .take(1)
            .foreach(
              v =>
                vehiclesForReposition.prepend(
                  (
                    v.asInstanceOf[BeamVehicle],
                    new SpaceTime(destination._1.taz.coord, arrivalTime),
                    destination._1.taz.tazId
                  )
              )
            )
        case None =>
      }
    }
    vehiclesForReposition.toList
  }

  override def collectData(time: Int, repositionManager: RepositionManager) = {
    beamSkimmer.observeVehicleAvailabilityByTAZ(
      time, repositionManager.getId, LABEL, repositionManager.getAvailableVehicles.queryAll().asScala.toList
    )
  }

}
