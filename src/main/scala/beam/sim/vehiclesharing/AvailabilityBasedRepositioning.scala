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
  beamServices: BeamServices,
  repositionManager: RepositionManager
) extends RepositionAlgorithm {

  case class RepositioningRequest(taz: TAZ, availableVehicles: Int, shortage: Int)
  val LABEL: String = "availability"
  val minAvailabilityMap = mutable.HashMap.empty[(Int, Id[TAZ]), Int]
  val timeBin = repositionManager.getREPTimeStep
  val orderingAvailVeh = Ordering.by[RepositioningRequest, Int](_.availableVehicles)
  val orderingShortage = Ordering.by[RepositioningRequest, Int](_.shortage)


  beamServices.tazTreeMap.getTAZs.foreach { taz =>
    (0 until 108000 by timeBin).foreach { i =>
      val availVal = beamSkimmer.getPreviousSkimPlusValues(i, i + timeBin, taz.tazId, repositionManager.getId, LABEL)
      val availValMin =
        availVal.drop(1).foldLeft(availVal.headOption.getOrElse(0.0).toInt)((minV, cur) => Math.min(minV, cur.toInt))
      minAvailabilityMap.put((i, taz.tazId), availValMin)
    }
  }

  override def collectData(time: Int) = {
    val vehicles = repositionManager.getAvailableVehiclesIndex.queryAll().asScala.toList
    beamSkimmer.observeVehicleAvailabilityByTAZ(time, repositionManager.getId, LABEL, vehicles)
  }

  private def getSkim(time: Int, idTAZ: Id[TAZ], label: String) = {
    beamSkimmer.getPreviousSkimPlusValues(time, time + timeBin, idTAZ, repositionManager.getId, label)
  }

  override def getVehiclesForReposition(now: Int): List[(BeamVehicle, SpaceTime, Id[TAZ], SpaceTime, Id[TAZ])] = {

    val oversuppliedTAZ = mutable.TreeSet.empty[RepositioningRequest](orderingAvailVeh)
    val undersuppliedTAZ = mutable.TreeSet.empty[RepositioningRequest](orderingShortage)

    val futureNow = now + timeBin
    beamServices.tazTreeMap.getTAZs.foreach { taz =>
      val inquiryVal = getSkim(futureNow, taz.tazId, "MobilityStatusInquiry").sum.toInt
      val boardingVal = getSkim(futureNow, taz.tazId, "Boarded").sum.toInt
      val availValMin = minAvailabilityMap((now, taz.tazId))
      val InquiryUnboarded = inquiryVal - boardingVal
      if (availValMin > 0) {
        oversuppliedTAZ.add(RepositioningRequest(taz, availValMin, 0))
      } else if (InquiryUnboarded > 0) {
        undersuppliedTAZ.add(RepositioningRequest(taz, 0, InquiryUnboarded))
      }
    }

    val ODs = new mutable.ListBuffer[(RepositioningRequest, RepositioningRequest, Int)]
    oversuppliedTAZ.foreach { org =>
      var destTimeOpt: Option[(RepositioningRequest, Int)] = None
      undersuppliedTAZ.foreach { dst =>
        val skim = beamSkimmer.getPreviousSkimValueOrDefault(now, BeamMode.CAR, org.taz.tazId, dst.taz.tazId)
        if (destTimeOpt.isEmpty || (destTimeOpt.isDefined && skim.time < destTimeOpt.get._2)) {
          destTimeOpt = Some((dst, skim.time))
        }
      }
      destTimeOpt foreach { case (dst, tt) => ODs.append((org, dst, tt)) }
    }

    val vehiclesForReposition = new mutable.ListBuffer[(BeamVehicle, SpaceTime, Id[TAZ], SpaceTime, Id[TAZ])]()
    ODs.foreach {
      case (org, dst, tt) =>
        val arrivalTime = now + tt
        val vehicles = repositionManager.getAvailableVehiclesIndex
          .queryAll()
          .asScala
          .filter(
            v =>
              org.taz == beamServices.tazTreeMap.getTAZ(
                v.asInstanceOf[BeamVehicle].spaceTime.loc.getX,
                v.asInstanceOf[BeamVehicle].spaceTime.loc.getY
            )
          )
          .map(
            v =>
              (
                v.asInstanceOf[BeamVehicle],
                SpaceTime(org.taz.coord, now),
                org.taz.tazId,
                SpaceTime(dst.taz.coord, arrivalTime),
                dst.taz.tazId
            )
          )
        vehiclesForReposition.appendAll(vehicles)
        val orgKey = (now, org.taz.tazId)
        val dstKey = (futureNow, dst.taz.tazId)
        minAvailabilityMap.update(orgKey, minAvailabilityMap(orgKey) - vehicles.size)
        minAvailabilityMap.update(dstKey, minAvailabilityMap(dstKey) + vehicles.size)
    }

    vehiclesForReposition.toList
  }

}
