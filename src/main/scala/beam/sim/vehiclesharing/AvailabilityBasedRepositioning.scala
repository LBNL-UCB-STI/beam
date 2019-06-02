package beam.sim.vehiclesharing
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.JavaConverters._
import scala.collection.mutable

private[vehiclesharing] class AvailabilityBasedRepositioning(
  beamSkimmer: BeamSkimmer,
  beamServices: BeamServices,
  repositionManager: RepositionManager
) extends RepositionAlgorithm {

  case class RepositioningRequest(taz: TAZ, availableVehicles: Int, shortage: Int)
  val minAvailabilityMap = mutable.HashMap.empty[(Int, Id[TAZ]), Int]
  val unboardedVehicleInquiry = mutable.HashMap.empty[(Int, Id[TAZ]), Int]
  val orderingAvailVeh = Ordering.by[RepositioningRequest, Int](_.availableVehicles)
  val orderingShortage = Ordering.by[RepositioningRequest, Int](_.shortage)
  val availability = "VEHAvailability"

  beamServices.tazTreeMap.getTAZs.foreach { taz =>
    (0 to 108000 / repositionManager.getREPTimeStep).foreach { i =>
      val time = i * repositionManager.getREPTimeStep
      val availVal = getSkim(time, taz.tazId, availability)
      val availValMin = availVal.drop(1).foldLeft(availVal.headOption.getOrElse(0.0).toInt) { (minV, cur) =>
        Math.min(minV, cur.toInt)
      }
      minAvailabilityMap.put((i, taz.tazId), availValMin)
      val inquiryVal = getSkim(time, taz.tazId, RepositionManager.inquiry).sum.toInt
      val boardingVal = getSkim(time, taz.tazId, RepositionManager.boarded).sum.toInt
      unboardedVehicleInquiry.put((i, taz.tazId), inquiryVal - boardingVal)
    }
  }
  override def collectData(time: Int) = {
    val curBin = time / repositionManager.getDataCollectTimeStep
    val vehicles = repositionManager.getAvailableVehiclesIndex.queryAll().asScala.toList
    beamSkimmer.observeVehicleAvailabilityByTAZ(curBin, repositionManager.getId, availability, vehicles)
  }

  override def collectData(time: Int, coord: Coord, label: String) = {
    val curBin = time / repositionManager.getDataCollectTimeStep
    beamSkimmer.countEventsByTAZ(curBin, coord, repositionManager.getId, label)
  }

  private def getSkim(time: Int, idTAZ: Id[TAZ], label: String) = {
    val fromBin = time / repositionManager.getDataCollectTimeStep
    val untilBin = (time + repositionManager.getREPTimeStep) / repositionManager.getDataCollectTimeStep
    beamSkimmer.getPreviousSkimPlusValues(fromBin, untilBin, idTAZ, repositionManager.getId, label)
  }

  override def getVehiclesForReposition(now: Int): List[(BeamVehicle, SpaceTime, Id[TAZ], SpaceTime, Id[TAZ])] = {

    val oversuppliedTAZ = mutable.TreeSet.empty[RepositioningRequest](orderingAvailVeh)
    val undersuppliedTAZ = mutable.TreeSet.empty[RepositioningRequest](orderingShortage)

    val nowRepBin = now / repositionManager.getREPTimeStep
    val futureRepBin = nowRepBin + 1
    beamServices.tazTreeMap.getTAZs.foreach { taz =>
      val availValMin = minAvailabilityMap((nowRepBin, taz.tazId))
      val InquiryUnboarded = unboardedVehicleInquiry((futureRepBin, taz.tazId))
      if (availValMin > 0) {
        oversuppliedTAZ.add(RepositioningRequest(taz, availValMin, 0))
      } else if (InquiryUnboarded > 0) {
        undersuppliedTAZ.add(RepositioningRequest(taz, 0, InquiryUnboarded))
      }
    }

    val ODs = new mutable.ListBuffer[(RepositioningRequest, RepositioningRequest, Int, Int)]

    while (oversuppliedTAZ.nonEmpty && undersuppliedTAZ.nonEmpty) {
      val org = oversuppliedTAZ.head
      var destTimeOpt: Option[(RepositioningRequest, Int)] = None
      undersuppliedTAZ.foreach { dst =>
        val skim = beamSkimmer.getTimeDistanceAndCost(
          org.taz.coord,
          dst.taz.coord,
          now,
          BeamMode.CAR,
          Id.create("Car", classOf[BeamVehicleType])
        )
        if (destTimeOpt.isEmpty || (destTimeOpt.isDefined && skim.time < destTimeOpt.get._2)) {
          destTimeOpt = Some((dst, skim.time))
        }
      }
      destTimeOpt foreach {
        case (dst, tt) =>
          val fleetSize = Math.min(org.availableVehicles, dst.shortage)
          oversuppliedTAZ.remove(org)
          if (org.availableVehicles > fleetSize) {
            oversuppliedTAZ.add(org.copy(availableVehicles = org.availableVehicles - fleetSize))
          }
          undersuppliedTAZ.remove(dst)
          if (dst.shortage > fleetSize) {
            undersuppliedTAZ.add(dst.copy(shortage = dst.shortage - fleetSize))
          }
          ODs.append((org, dst, tt, fleetSize))
      }
    }

    val vehiclesForReposition = new mutable.ListBuffer[(BeamVehicle, SpaceTime, Id[TAZ], SpaceTime, Id[TAZ])]()
    val rand = new scala.util.Random(System.currentTimeMillis())
    ODs.foreach {
      case (org, dst, tt, fs) =>
        val arrivalTime = now + tt
        val vehicles = repositionManager.getAvailableVehiclesIndex
          .queryAll()
          .asScala
          .take(fs)
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
                SpaceTime(getRandomLocationWithinRadius(dst.taz, rand), arrivalTime),
                dst.taz.tazId
            )
          )
        vehiclesForReposition.appendAll(vehicles)
        val orgKey = (nowRepBin, org.taz.tazId)
        val dstKey = (futureRepBin, dst.taz.tazId)
        minAvailabilityMap.update(orgKey, minAvailabilityMap(orgKey) - vehicles.size)
      //minAvailabilityMap.update(dstKey, minAvailabilityMap(dstKey) + vehicles.size)
    }

    vehiclesForReposition.toList
  }

  def getRandomLocationWithinRadius(taz: TAZ, rand: scala.util.Random): Coord = {
    val radius = Math.sqrt(taz.areaInSquareMeters / Math.PI)
    val a = 2 * Math.PI * rand.nextDouble()
    val r = radius * Math.sqrt(rand.nextDouble())
    val x = r * Math.cos(a)
    val y = r * Math.sin(a)
    new Coord(taz.coord.getX + x, taz.coord.getY + y)
  }

}
