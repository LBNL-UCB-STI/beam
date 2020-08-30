package beam.sim.vehiclesharing
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.router.Modes.BeamMode
import beam.router.skim.{ODSkims, Skims, TAZSkimmer, TAZSkims}
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id
import scala.collection.mutable

case class AvailabilityBasedRepositioning(
  repositionTimeBin: Int,
  statTimeBin: Int,
  matchLimit: Int,
  vehicleManager: Id[VehicleManager],
  beamServices: BeamServices
) extends RepositionAlgorithm {

  case class RepositioningRequest(taz: TAZ, availableVehicles: Int, shortage: Int)
  val minAvailabilityMap = mutable.HashMap.empty[(Int, Id[TAZ]), Int]
  val unboardedVehicleInquiry = mutable.HashMap.empty[(Int, Id[TAZ]), Int]
  val orderingAvailVeh: Ordering[RepositioningRequest] = Ordering.by[RepositioningRequest, Int](_.availableVehicles)
  val orderingShortage: Ordering[RepositioningRequest] = Ordering.by[RepositioningRequest, Int](_.shortage)

  beamServices.beamScenario.tazTreeMap.getTAZs.foreach { taz =>
    (0 to 108000 / repositionTimeBin).foreach { i =>
      val time = i * repositionTimeBin
      val availVal = getCollectedDataFromPreviousSimulation(time, taz.tazId, RepositionManager.availability)
      val availValMin = availVal.drop(1).foldLeft(availVal.headOption.map(_.observations).getOrElse(0)) { (minV, cur) =>
        Math.min(minV, cur.observations)
      }
      minAvailabilityMap.put((i, taz.tazId), availValMin)
      val inquiryVal =
        getCollectedDataFromPreviousSimulation(time, taz.tazId, RepositionManager.inquiry).map(_.observations).sum
      val boardingVal =
        getCollectedDataFromPreviousSimulation(time, taz.tazId, RepositionManager.boarded).map(_.observations).sum
      unboardedVehicleInquiry.put((i, taz.tazId), inquiryVal - boardingVal)
    }
  }

  def getCollectedDataFromPreviousSimulation(
    time: Int,
    idTAZ: Id[TAZ],
    label: String
  ): Vector[TAZSkimmer.TAZSkimmerInternal] = {
    val fromBin = time / statTimeBin
    val untilBin = (time + repositionTimeBin) / statTimeBin
    (fromBin until untilBin)
      .map(i => beamServices.skims.taz_skimmer.getLatestSkimByTAZ(i, idTAZ, vehicleManager.toString, label))
      .toVector
      .flatten
  }

  override def getVehiclesForReposition(
    now: Int,
    timeBin: Int,
    availableFleet: List[BeamVehicle]
  ): List[(BeamVehicle, SpaceTime, Id[TAZ], SpaceTime, Id[TAZ])] = {

    val oversuppliedTAZ = mutable.TreeSet.empty[RepositioningRequest](orderingAvailVeh)
    val undersuppliedTAZ = mutable.TreeSet.empty[RepositioningRequest](orderingShortage)

    val nowRepBin = now / timeBin
    val futureRepBin = nowRepBin + 1
    beamServices.beamScenario.tazTreeMap.getTAZs.foreach { taz =>
      val availValMin = minAvailabilityMap((nowRepBin, taz.tazId))
      val InquiryUnboarded = unboardedVehicleInquiry((futureRepBin, taz.tazId))
      if (availValMin > 0) {
        oversuppliedTAZ.add(RepositioningRequest(taz, availValMin, 0))
      } else if (InquiryUnboarded > 0) {
        undersuppliedTAZ.add(RepositioningRequest(taz, 0, InquiryUnboarded))
      }
    }

    val topOversuppliedTAZ = oversuppliedTAZ.take(matchLimit)
    val topUndersuppliedTAZ = undersuppliedTAZ.take(matchLimit)
    val ODs = new mutable.ListBuffer[(RepositioningRequest, RepositioningRequest, Int, Int)]
    while (topOversuppliedTAZ.nonEmpty && topUndersuppliedTAZ.nonEmpty) {
      val org = topOversuppliedTAZ.head
      var destTimeOpt: Option[(RepositioningRequest, Int)] = None
      topUndersuppliedTAZ.foreach { dst =>
        val skim = beamServices.skims.od_skimmer.getTimeDistanceAndCost(
          org.taz.coord,
          dst.taz.coord,
          now,
          BeamMode.CAR,
          Id.create( // FIXME Vehicle type borrowed from ridehail -- pass the vehicle type of the car sharing fleet instead
            beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
            classOf[BeamVehicleType]
          ),
          beamServices.beamScenario
        )
        if (destTimeOpt.isEmpty || (destTimeOpt.isDefined && skim.time < destTimeOpt.get._2)) {
          destTimeOpt = Some((dst, skim.time))
        }
      }
      destTimeOpt foreach {
        case (dst, tt) =>
          val fleetSize = Math.min(org.availableVehicles, dst.shortage)
          topOversuppliedTAZ.remove(org)
          if (org.availableVehicles > fleetSize) {
            topOversuppliedTAZ.add(org.copy(availableVehicles = org.availableVehicles - fleetSize))
          }
          topUndersuppliedTAZ.remove(dst)
          if (dst.shortage > fleetSize) {
            topUndersuppliedTAZ.add(dst.copy(shortage = dst.shortage - fleetSize))
          }
          ODs.append((org, dst, tt, fleetSize))
      }
    }

    val vehiclesForReposition = mutable.ListBuffer.empty[(BeamVehicle, SpaceTime, Id[TAZ], SpaceTime, Id[TAZ])]
    val rand = new scala.util.Random(System.currentTimeMillis())
    var fleetTemp = availableFleet
    ODs.foreach {
      case (org, dst, tt, fleetSizeToReposition) =>
        val arrivalTime = now + tt
        val vehiclesForRepositionTemp =
          mutable.ListBuffer.empty[(BeamVehicle, SpaceTime, Id[TAZ], SpaceTime, Id[TAZ])]
        fleetTemp
          .filter(
            v =>
              org.taz == beamServices.beamScenario.tazTreeMap
                .getTAZ(v.spaceTime.loc.getX, v.spaceTime.loc.getY)
          )
          .take(fleetSizeToReposition)
          .map(
            (
              _,
              SpaceTime(org.taz.coord, now),
              org.taz.tazId,
              SpaceTime(TAZTreeMap.randomLocationInTAZ(dst.taz, rand), arrivalTime),
              dst.taz.tazId
            )
          )
          .foreach(vehiclesForRepositionTemp.append(_))
        val orgKey = (nowRepBin, org.taz.tazId)
        minAvailabilityMap.update(orgKey, minAvailabilityMap(orgKey) - vehiclesForRepositionTemp.size)
        fleetTemp = fleetTemp.filter(x => !vehiclesForRepositionTemp.exists(_._1 == x))
        vehiclesForReposition.appendAll(vehiclesForRepositionTemp)
    }

    vehiclesForReposition.toList
  }
}
