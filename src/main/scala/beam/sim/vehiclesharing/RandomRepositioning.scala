package beam.sim.vehiclesharing
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id

import scala.collection.mutable

case class RandomRepositioning(
  repositionTimeBin: Int,
  statTimeBin: Int,
  matchLimit: Int,
  vehicleManager: Id[VehicleManager],
  beamServices: BeamServices
) extends RepositionAlgorithm {

  val random = new scala.util.Random

  override def getVehiclesForReposition(
    now: Int,
    timeBin: Int,
    availableFleet: List[BeamVehicle]
  ): List[(BeamVehicle, SpaceTime, Id[TAZ], SpaceTime, Id[TAZ])] = {
    var supplierTAZ = mutable.ListBuffer.empty[TAZ]
    var destinationTAZ = mutable.ListBuffer.empty[TAZ]

    beamServices.beamScenario.tazTreeMap.getTAZs.foreach { taz =>
      if (random.nextBoolean()) {
        supplierTAZ += taz
      }
      destinationTAZ += taz
    }

    val maxSupplierTAZ = supplierTAZ.take(matchLimit)
    val maxDestinationTAZ = destinationTAZ.take(matchLimit)
    val ODs = new mutable.ListBuffer[(TAZ, TAZ, Int)]

    while (maxSupplierTAZ.nonEmpty && maxDestinationTAZ.nonEmpty) {
      val org = maxSupplierTAZ.head
      var destTimeOpt: Option[(TAZ, Int)] = None
      maxDestinationTAZ -= org
      maxDestinationTAZ.foreach { dst =>
        val skim = beamServices.skims.od_skimmer.getTimeDistanceAndCost(
          org.coord,
          dst.coord,
          now,
          BeamMode.CAR,
          Id.create(
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
          maxSupplierTAZ -= org
          maxDestinationTAZ -= dst
          ODs.append((org, dst, tt))
      }
    }

    val vehiclesForReposition = mutable.ListBuffer.empty[(BeamVehicle, SpaceTime, Id[TAZ], SpaceTime, Id[TAZ])]
    val rand = new scala.util.Random(System.currentTimeMillis())
    ODs.foreach {
      case (org, dst, tt) =>
        val arrivalTime = now + tt
        val vehiclesForRepositionTemp = mutable.ListBuffer.empty[(BeamVehicle, SpaceTime, Id[TAZ], SpaceTime, Id[TAZ])]

        availableFleet
          .filter(
            v =>
              org == beamServices.beamScenario.tazTreeMap
                .getTAZ(v.spaceTime.loc.getX, v.spaceTime.loc.getY)
          )
          .map(
            (
              _,
              SpaceTime(org.coord, now),
              org.tazId,
              SpaceTime(TAZTreeMap.randomLocationInTAZ(dst, rand), arrivalTime),
              dst.tazId
            )
          )
          .foreach(vehiclesForRepositionTemp.append(_))
        vehiclesForReposition.appendAll(vehiclesForRepositionTemp)
    }
    vehiclesForReposition.toList
  }
}
