package beam.sim.vehiclesharing
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
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
  ): List[(BeamVehicle, SpaceTime, Id[TAZ], SpaceTime, Id[TAZ])] = {
    val relocate = beamServices.iterationNumber > 0 || beamServices.beamConfig.beam.warmStart.enabled
    val oversuppliedTAZ =
      mutable.TreeSet.empty[RepositioningRequest](Ordering.by[RepositioningRequest, Int](_.availableVehicles))
    val undersuppliedTAZ =
      mutable.TreeSet.empty[RepositioningRequest](Ordering.by[RepositioningRequest, Int](_.shortage))

    beamServices.tazTreeMap.getTAZs.foreach { taz =>
      if (relocate) {
        // availability
        val availabilityVect =
          beamSkimmer.getPreviousSkimPlusValues(startTime, endTime, taz.tazId, repositionManager.getId, LABEL)
        val availability = availabilityVect.drop(1).foldLeft(availabilityVect.headOption.getOrElse(0.0).toInt) {
          (minV, cur) =>
            Math.min(minV, cur.toInt)
        }

        // demand
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

        // decision
        if (availability > 0 && availability < Int.MaxValue) {
          oversuppliedTAZ.add(RepositioningRequest(taz, availability, 0))
        } else if (availability == 0) {
          undersuppliedTAZ.add(RepositioningRequest(taz, 0, 1))
        }

      }
    }

    val ODs = new mutable.ListBuffer[(RepositioningRequest, RepositioningRequest, Int)]
    oversuppliedTAZ.foreach { org =>
      var destTimeOpt: Option[(RepositioningRequest, Int)] = None
      undersuppliedTAZ.foreach { dst =>
        val skim = beamSkimmer.getPreviousSkimValueOrDefault(startTime, BeamMode.CAR, org.taz.tazId, dst.taz.tazId)
        if (destTimeOpt.isEmpty || (destTimeOpt.isDefined && skim.time < destTimeOpt.get._2)) {
          destTimeOpt = Some((dst, skim.time))
        }
      }
      destTimeOpt match {
        case Some((dst, time)) => ODs.append((org, dst, time))
        case _                 => // none
      }
    }

    val vehiclesForReposition = new mutable.ListBuffer[(BeamVehicle, SpaceTime, Id[TAZ], SpaceTime, Id[TAZ])]()
    ODs.foreach { case (org, dst, time) =>
      val arrivalTime = startTime + time
      val vehicles = repositionManager.getAvailableVehiclesIndex.queryAll().asScala
      vehiclesForReposition.appendAll(vehicles.filter(v =>
        org.taz == beamServices.tazTreeMap.getTAZ(
          v.asInstanceOf[BeamVehicle].spaceTime.loc.getX,
          v.asInstanceOf[BeamVehicle].spaceTime.loc.getY)).map(
        v => (
          v.asInstanceOf[BeamVehicle],
          SpaceTime(org.taz.coord, startTime),
          org.taz.tazId,
          SpaceTime(dst.taz.coord, arrivalTime),
          dst.taz.tazId)
      ))
    }

    vehiclesForReposition.toList
  }

  override def collectData(time: Int, repositionManager: RepositionManager) = {
    beamSkimmer.observeVehicleAvailabilityByTAZ(
      time,
      repositionManager.getId,
      LABEL,
      repositionManager.getAvailableVehiclesIndex.queryAll().asScala.toList
    )
  }

}
