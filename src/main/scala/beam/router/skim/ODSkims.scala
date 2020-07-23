package beam.router.skim

import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{
  CAR,
  CAV,
  DRIVE_TRANSIT,
  RIDE_HAIL,
  RIDE_HAIL_POOLED,
  RIDE_HAIL_TRANSIT,
  TRANSIT,
  WALK_TRANSIT
}
import beam.router.model.{BeamLeg, BeamPath}
import beam.router.skim.ODSkimmer.{ExcerptData, ODSkimmerInternal, ODSkimmerKey, Skim}
import beam.router.skim.SkimsUtils.{distanceAndTime, getRideHailCost, timeToBin}
import beam.sim.config.BeamConfig
import beam.sim.{BeamScenario, BeamServices}
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.immutable

case class ODSkims(beamConfig: BeamConfig, beamScenario: BeamScenario) extends AbstractSkimmerReadOnly {

  def getSkimDefaultValue(
    mode: BeamMode,
    originUTM: Location,
    destinationUTM: Location,
    departureTime: Int,
    vehicleTypeId: Id[BeamVehicleType],
    beamScenario: BeamScenario
  ): Skim = {
    val (travelDistance, travelTime) = distanceAndTime(mode, originUTM, destinationUTM)
    val votMultiplier: Double = mode match {
      case CAV => beamConfig.beam.agentsim.agents.modalBehaviors.modeVotMultiplier.CAV
      case _   => 1.0
    }
    val travelCost: Double = mode match {
      case CAR | CAV =>
        DrivingCost.estimateDrivingCost(
          new BeamLeg(
            departureTime,
            mode,
            travelTime,
            new BeamPath(IndexedSeq(), IndexedSeq(), None, null, null, travelDistance)
          ),
          beamScenario.vehicleTypes(vehicleTypeId),
          beamScenario.fuelTypePrices
        )
      case RIDE_HAIL =>
        beamConfig.beam.agentsim.agents.rideHail.defaultBaseCost + beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMile * travelDistance / 1609.0 + beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMinute * travelTime / 60.0
      case RIDE_HAIL_POOLED =>
        beamConfig.beam.agentsim.agents.rideHail.pooledBaseCost + beamConfig.beam.agentsim.agents.rideHail.pooledCostPerMile * travelDistance / 1609.0 + beamConfig.beam.agentsim.agents.rideHail.pooledCostPerMinute * travelTime / 60.0
      case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT | RIDE_HAIL_TRANSIT => 0.25 * travelDistance / 1609
      case _                                                          => 0.0
    }
    Skim(
      travelTime,
      travelTime * votMultiplier,
      travelCost + travelTime * beamConfig.beam.agentsim.agents.modalBehaviors.defaultValueOfTime / 3600,
      travelDistance,
      travelCost,
      0,
      0.0 // TODO get default energy information
    )
  }

  def getRideHailPoolingTimeAndCostRatios(
    origin: Location,
    destination: Location,
    departureTime: Int,
    vehicleTypeId: org.matsim.api.core.v01.Id[BeamVehicleType],
    beamServices: BeamServices
  ): (Double, Double) = {
    val tazTreeMap = beamServices.beamScenario.tazTreeMap
    val beamConfig = beamServices.beamConfig
    val origTaz = tazTreeMap.getTAZ(origin.getX, origin.getY).tazId
    val destTaz = tazTreeMap.getTAZ(destination.getX, destination.getY).tazId
    val solo = getSkimValue(departureTime, RIDE_HAIL, origTaz, destTaz) match {
      case Some(skimValue) if skimValue.observations > 5 =>
        skimValue
      case _ =>
        val (travelDistance, travelTime) = distanceAndTime(RIDE_HAIL, origin, destination)
        ODSkimmerInternal(
          travelTimeInS = travelTime.toDouble,
          generalizedTimeInS = 0,
          generalizedCost = 0,
          distanceInM = travelDistance.toDouble,
          cost = getRideHailCost(RIDE_HAIL, travelDistance, travelTime, beamConfig),
          energy = 0.0,
          observations = 0,
          iterations = beamServices.matsimServices.getIterationNumber
        )
    }
    val pooled = getSkimValue(departureTime, RIDE_HAIL_POOLED, origTaz, destTaz) match {
      case Some(skimValue) if skimValue.observations > 5 =>
        skimValue
      case _ =>
        ODSkimmerInternal(
          travelTimeInS = solo.travelTimeInS * 1.1,
          generalizedTimeInS = 0,
          generalizedCost = 0,
          distanceInM = solo.distanceInM,
          cost = getRideHailCost(RIDE_HAIL_POOLED, solo.distanceInM, solo.travelTimeInS, beamConfig),
          energy = 0.0,
          observations = 0,
          iterations = beamServices.matsimServices.getIterationNumber
        )
    }
    val timeFactor = if (solo.travelTimeInS > 0.0) { pooled.travelTimeInS / solo.travelTimeInS } else { 1.0 }
    val costFactor = if (solo.cost > 0.0) { pooled.cost / solo.cost } else { 1.0 }
    (timeFactor, costFactor)
  }

  def getTimeDistanceAndCost(
    originUTM: Location,
    destinationUTM: Location,
    departureTime: Int,
    mode: BeamMode,
    vehicleTypeId: Id[BeamVehicleType],
    beamScenario: BeamScenario
  ): Skim = {
    val origTaz = beamScenario.tazTreeMap.getTAZ(originUTM.getX, originUTM.getY).tazId
    val destTaz = beamScenario.tazTreeMap.getTAZ(destinationUTM.getX, destinationUTM.getY).tazId
    getSkimValue(departureTime, mode, origTaz, destTaz) match {
      case Some(skimValue) =>
        skimValue.toSkimExternal
      case None =>
        getSkimDefaultValue(
          mode,
          originUTM,
          new Coord(destinationUTM.getX, destinationUTM.getY),
          departureTime,
          vehicleTypeId,
          beamScenario
        )
    }
  }

  def getExcerptData(
    timePeriodString: String,
    hoursIncluded: List[Int],
    origin: TAZ,
    destination: TAZ,
    mode: BeamMode,
    dummyId: Id[BeamVehicleType],
    skim: immutable.Map[ODSkimmerKey, ODSkimmerInternal]
  ): ExcerptData = {
    val individualSkims = hoursIncluded.map { timeBin =>
      skim
        .get(ODSkimmerKey(timeBin, mode, origin.tazId, destination.tazId))
        .map(_.toSkimExternal)
        .getOrElse {
          val adjustedDestCoord = if (origin.equals(destination)) {
            new Coord(
              origin.coord.getX,
              origin.coord.getY + Math.sqrt(origin.areaInSquareMeters) / 2.0
            )
          } else {
            destination.coord
          }
          getSkimDefaultValue(
            mode,
            origin.coord,
            adjustedDestCoord,
            timeBin * 3600,
            dummyId,
            beamScenario
          )
        }
    }
    val weights = individualSkims.map(sk => Math.max(sk.count, 1).toDouble)
    val sumWeights = weights.sum
    val weightedDistance = individualSkims.map(_.distance).zip(weights).map(tup => tup._1 * tup._2).sum / sumWeights
    val weightedTime = individualSkims.map(_.time).zip(weights).map(tup => tup._1 * tup._2).sum / sumWeights
    val weightedGeneralizedTime = individualSkims
      .map(_.generalizedTime)
      .zip(weights)
      .map(tup => tup._1 * tup._2)
      .sum / sumWeights
    val weightedCost = individualSkims.map(_.cost).zip(weights).map(tup => tup._1 * tup._2).sum / sumWeights
    val weightedGeneralizedCost = individualSkims
      .map(_.generalizedCost)
      .zip(weights)
      .map(tup => tup._1 * tup._2)
      .sum / sumWeights
    val weightedEnergy = individualSkims.map(_.energy).zip(weights).map(tup => tup._1 * tup._2).sum / sumWeights

    ExcerptData(
      timePeriodString = timePeriodString,
      mode = mode,
      originTazId = origin.tazId,
      destinationTazId = destination.tazId,
      weightedTime = weightedTime,
      weightedGeneralizedTime = weightedGeneralizedTime,
      weightedCost = weightedCost,
      weightedGeneralizedCost = weightedGeneralizedCost,
      weightedDistance = weightedDistance,
      sumWeights = sumWeights,
      weightedEnergy = weightedEnergy
    )
  }

  private def getSkimValue(time: Int, mode: BeamMode, orig: Id[TAZ], dest: Id[TAZ]): Option[ODSkimmerInternal] = {
    pastSkims
      .map(_.get(ODSkimmerKey(timeToBin(time), mode, orig, dest)))
      .headOption
      .getOrElse(aggregatedSkim.get(ODSkimmerKey(timeToBin(time), mode, orig, dest)))
      .map(_.asInstanceOf[ODSkimmerInternal])
  }

}
