package beam.router.skim.readonly

import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE_TRANSIT, CAR, CAV, DRIVE_TRANSIT, RIDE_HAIL, RIDE_HAIL_POOLED, RIDE_HAIL_TRANSIT, TRANSIT, WALK_TRANSIT}
import beam.router.skim.SkimsUtils.{distanceAndTime, getRideHailCost, timeToBin}
import beam.router.skim.core.AbstractSkimmerReadOnly
import beam.router.skim.core.ODSkimmer.{ExcerptData, ODSkimmerInternal, ODSkimmerKey, Skim}
import beam.sim.config.BeamConfig
import beam.sim.{BeamHelper, BeamScenario, BeamServices}
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.immutable

case class ODSkims(beamConfig: BeamConfig, beamScenario: BeamScenario) extends AbstractSkimmerReadOnly {

//  val skimsDebugCalculationHeader = {
////    "origin,destination,departureTime,mode,result"
//    "time,hour,mode,origin,destination,pastSkimsSize,pastSkimValue,aggregatedSkimsSkize,skimValue"
//  }
//  val skimsDebugCalculation = scala.collection.mutable.ListBuffer.empty[Seq[String]]
//
//  def addStringToDebugCalculation2(
//    time: String,
//    skimkey: String,
//    pastSkimsSize: String,
//    pastSkimValue: String,
//    aggregatedFromPastSkimsSize: String,
//    skimValue: String
//  ) = {
//    synchronized {
//      skimsDebugCalculation.append(
//        Seq(time, skimkey, pastSkimsSize, pastSkimValue, aggregatedFromPastSkimsSize, skimValue)
//      )
//    }
//  }
//
//  def addStringToDebugCalculation(
//    origin: Id[TAZ],
//    destination: Id[TAZ],
//    departureTime: Int,
//    mode: BeamMode,
//    result: String
//  ) = {
//    val theSeq = Seq(origin.toString, destination.toString, departureTime.toString, mode.toString, result)
//    //    synchronized {
//    //      skimsDebugCalculation.append(
//    //        Seq(origin.toString, destination.toString, departureTime.toString, mode.toString, result)
//    //      )
//    //    }
//  }

  def getSkimDefaultValue(
    mode: BeamMode,
    originUTM: Location,
    destinationUTM: Location,
    beamVehicleType: BeamVehicleType,
    fuelPrice: Double
  ): Skim = {
    val (travelDistance, travelTime) = distanceAndTime(mode, originUTM, destinationUTM)
    val votMultiplier: Double = mode match {
      case CAV => beamConfig.beam.agentsim.agents.modalBehaviors.modeVotMultiplier.CAV
      case _   => 1.0
    }
    val travelCost: Double = mode match {
      case CAR | CAV =>
        DrivingCost.estimateDrivingCost(
          travelDistance,
          travelTime,
          beamVehicleType,
          fuelPrice
        )
      case RIDE_HAIL =>
        beamConfig.beam.agentsim.agents.rideHail.defaultBaseCost + beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMile * travelDistance / 1609.0 + beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMinute * travelTime / 60.0
      case RIDE_HAIL_POOLED =>
        beamConfig.beam.agentsim.agents.rideHail.pooledBaseCost + beamConfig.beam.agentsim.agents.rideHail.pooledCostPerMile * travelDistance / 1609.0 + beamConfig.beam.agentsim.agents.rideHail.pooledCostPerMinute * travelTime / 60.0
      case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT | RIDE_HAIL_TRANSIT | BIKE_TRANSIT => 0.25 * travelDistance / 1609
      case _                                                                         => 0.0
    }
    val calculatedDefaultValue = Skim(
      travelTime,
      travelTime * votMultiplier,
      travelCost + travelTime * beamConfig.beam.agentsim.agents.modalBehaviors.defaultValueOfTime / 3600,
      travelDistance,
      travelCost,
      0,
      0.0 // TODO get default energy information
    )
    val maxSkims = Skim(Int.MaxValue, Double.MaxValue, Double.MaxValue, Double.MaxValue, Double.MaxValue)

    if (
      beamConfig.beam.agentsim.agents.tripBehaviors.mulitnomialLogit.return_max_skims_instead_of_calculated_for_missing_OD_pairs
    ) {
      maxSkims
    } else {
      calculatedDefaultValue
    }
  }

  def getRideHailPoolingTimeAndCostRatios(
    origin: Location,
    destination: Location,
    departureTime: Int,
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
          level4CavTravelTimeScalingFactor = 1.0,
          observations = 0,
          iterations = beamServices.matsimServices.getIterationNumber
        )
    }
    val pooled = getSkimValue(departureTime, RIDE_HAIL_POOLED, origTaz, destTaz) match {
      case Some(skimValue) if skimValue.observations > 5 =>
        skimValue
      case _ =>
        val poolingTravelTimeOveheadFactor =
          beamConfig.beam.router.skim.origin_destination_skimmer.poolingTravelTimeOveheadFactor
        ODSkimmerInternal(
          travelTimeInS = solo.travelTimeInS * poolingTravelTimeOveheadFactor,
          generalizedTimeInS = 0,
          generalizedCost = 0,
          distanceInM = solo.distanceInM,
          cost = getRideHailCost(
            RIDE_HAIL_POOLED,
            solo.distanceInM,
            solo.travelTimeInS * poolingTravelTimeOveheadFactor,
            beamConfig
          ),
          energy = 0.0,
          level4CavTravelTimeScalingFactor = 1.0,
          observations = 0,
          iterations = beamServices.matsimServices.getIterationNumber
        )
    }
    val timeFactor = if (solo.travelTimeInS > 0.0) { pooled.travelTimeInS / solo.travelTimeInS }
    else { 1.0 }
    val costFactor = if (solo.cost > 0.0) { pooled.cost / solo.cost }
    else { 1.0 }
    (timeFactor, costFactor)
  }

  def getTimeDistanceAndCost(
    originUTM: Location,
    destinationUTM: Location,
    departureTime: Int,
    mode: BeamMode,
    vehicleTypeId: Id[BeamVehicleType],
    vehicleType: BeamVehicleType,
    fuelPrice: Double,
    maybeOrigTazForPerformanceImprovement: Option[Id[TAZ]] =
      None, //If multiple times the same origin/destination is used, it
    maybeDestTazForPerformanceImprovement: Option[Id[TAZ]] =
      None //is better to pass them here to avoid accessing treeMap unnecessarily multiple times
  ): Skim = {
    val origTaz = maybeOrigTazForPerformanceImprovement.getOrElse(
      beamScenario.tazTreeMap.getTAZ(originUTM.getX, originUTM.getY).tazId
    )
    val destTaz = maybeDestTazForPerformanceImprovement.getOrElse(
      beamScenario.tazTreeMap.getTAZ(destinationUTM.getX, destinationUTM.getY).tazId
    )
    getSkimValue(departureTime, mode, origTaz, destTaz) match {
      case Some(skimValue) =>
        beamScenario.vehicleTypes.get(vehicleTypeId) match {
          case Some(vehicleType) if vehicleType.automationLevel == 4 =>
//            addStringToDebugCalculation(origTaz, destTaz, departureTime, mode, "Found - SkimExternalForLevel4CAV")
            skimValue.toSkimExternalForLevel4CAV
          case _ =>
//            addStringToDebugCalculation(origTaz, destTaz, departureTime, mode, "Found - SkimExternal")
            skimValue.toSkimExternal
        }
      case None =>
//        if (
        //          beamConfig.beam.agentsim.agents.tripBehaviors.mulitnomialLogit.return_max_skims_instead_of_calculated_for_missing_OD_pairs
        //        ) {
        //          addStringToDebugCalculation(origTaz, destTaz, departureTime, mode, "NotFound - MAX Skims")
        //        } else {
        //          addStringToDebugCalculation(origTaz, destTaz, departureTime, mode, "NotFound - Default Calculated Skims")
        //        }
        getSkimDefaultValue(
          mode,
          originUTM,
          new Coord(destinationUTM.getX, destinationUTM.getY),
          vehicleType,
          fuelPrice
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
        .get(ODSkimmerKey(timeBin, mode, origin.tazId.toString, destination.tazId.toString))
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
          val vehicleType: BeamVehicleType = beamScenario.vehicleTypes(dummyId)
          val fuelPrice = beamScenario.fuelTypePrices(vehicleType.primaryFuelType)

          getSkimDefaultValue(
            mode,
            origin.coord,
            adjustedDestCoord,
            vehicleType,
            fuelPrice
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
    val weightedTravelTimeScaleFactor = individualSkims
      .map(_.level4CavTravelTimeScalingFactor)
      .zip(weights)
      .map(tup => tup._1 * tup._2)
      .sum / sumWeights

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
      weightedEnergy = weightedEnergy,
      weightedLevel4TravelTimeScaleFactor = weightedTravelTimeScaleFactor
    )
  }

  private def getSkimValue(time: Int, mode: BeamMode, orig: Id[TAZ], dest: Id[TAZ]): Option[ODSkimmerInternal] = {
    val skimKey = ODSkimmerKey(timeToBin(time), mode, orig.toString, dest.toString)
    val maybePastSkims = pastSkims.get(currentIteration - 1)
    val maybePastSkim = maybePastSkims.flatMap(_.get(skimKey))
    val maybeSkim = maybePastSkim match {
      case Some(_) => maybePastSkim
      case None    => aggregatedFromPastSkims.get(skimKey)
    }

//    addStringToDebugCalculation2(
//      time.toString,
//      skimKey.toCsv,
//      maybePastSkims.map(_.size.toString).getOrElse("empty"),
//      maybePastSkim.map(_.toString).getOrElse("empty"),
//      aggregatedFromPastSkims.size.toString,
//      "\"" + maybeSkim.map(_.toString).getOrElse("empty") + "\""
//    )

    maybeSkim.asInstanceOf[Option[ODSkimmerInternal]]
  }

}

object ODSkims extends BeamHelper {

  def main(args: Array[String]): Unit = {
    val (_, config) = prepareConfig(args, true)
    val (
      _,
      _,
      beamScenario: BeamScenario,
      services: BeamServices,
      _
    ) = prepareBeamService(config, None)

    val skims = services.skims.od_skimmer

    val originUTM = new Coord(550552.4675435651, 4184258.471015979)
    val destinationUTM = new Coord(551444.9780408981, 4176179.0673731146)
    val departureTime = 19200
    val mode = BeamMode.CAR
    val vehicleTypeId = Id.create("Car", classOf[BeamVehicleType])

    println("Ready")
    val s = System.currentTimeMillis()
    var total: Long = 0
    val count: Int = 10000000
    val vehicleType: BeamVehicleType = beamScenario.vehicleTypes(vehicleTypeId)
    val fuelPrice: Double = beamScenario.fuelTypePrices(vehicleType.primaryFuelType)

    (1 to count).foreach { _ =>
      val r = BeamRouter.computeTravelTimeAndDistanceAndCost(
        originUTM,
        destinationUTM,
        departureTime,
        mode,
        vehicleTypeId,
        vehicleType,
        fuelPrice,
        beamScenario,
        skims
      )
      total += r.count
    }
    val e = System.currentTimeMillis()
    val diff = e - s
    println(s"Took: $diff ms for $count, AVG per call: ${diff.toDouble / count}")
  }
}
