package beam.router.skim

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, CAV, DRIVE_TRANSIT, RIDE_HAIL, RIDE_HAIL_POOLED, RIDE_HAIL_TRANSIT, TRANSIT, WALK, WALK_TRANSIT}
import beam.router.skim.ODSkimmer.{ExcerptData, ODSkimmerInternal, ODSkimmerKey, getSkimDefaultValue, rdOnlyAggregatedSkim, rdOnlyPastSkims}
import beam.sim.BeamServices
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.immutable

object ODSkimmerUtils {

  // 22.2 mph (9.924288 meter per second), is the average speed in cities
  //TODO better estimate can be drawn from city size
  // source: https://www.mitpressjournals.org/doi/abs/10.1162/rest_a_00744
  val carSpeedMeterPerSec: Double = 9.924288
  // 12.1 mph (5.409184 meter per second), is average bus speed
  // source: https://www.apta.com/resources/statistics/Documents/FactBook/2017-APTA-Fact-Book.pdf
  // assuming for now that it includes the headway
  val transitSpeedMeterPerSec: Double = 5.409184
  val bicycleSpeedMeterPerSec: Double = 3
  // 3.1 mph -> 1.38 meter per second
  val walkSpeedMeterPerSec: Double = 1.38
  // 940.6 Traffic Signal Spacing, Minor is 1,320 ft => 402.336 meters
  val trafficSignalSpacing: Double = 402.336
  // average waiting time at an intersection is 17.25 seconds
  // source: https://pumas.nasa.gov/files/01_06_00_1.pdf
  val waitingTimeAtAnIntersection: Double = 17.25

  val speedMeterPerSec: Map[BeamMode, Double] = Map(
    CAV               -> carSpeedMeterPerSec,
    CAR               -> carSpeedMeterPerSec,
    WALK              -> walkSpeedMeterPerSec,
    BIKE              -> bicycleSpeedMeterPerSec,
    WALK_TRANSIT      -> transitSpeedMeterPerSec,
    DRIVE_TRANSIT     -> transitSpeedMeterPerSec,
    RIDE_HAIL         -> carSpeedMeterPerSec,
    RIDE_HAIL_POOLED  -> carSpeedMeterPerSec,
    RIDE_HAIL_TRANSIT -> transitSpeedMeterPerSec,
    TRANSIT           -> transitSpeedMeterPerSec
  )

  def timeToBin(departTime: Int): Int = {
    Math.floorMod(Math.floor(departTime.toDouble / 3600.0).toInt, 24)
  }

  def timeToBin(departTime: Int, timeWindow: Int): Int = departTime / timeWindow

  def distanceAndTime(mode: BeamMode, originUTM: Location, destinationUTM: Location) = {
    val speed = mode match {
      case CAR | CAV | RIDE_HAIL                                      => carSpeedMeterPerSec
      case RIDE_HAIL_POOLED                                           => carSpeedMeterPerSec / 1.1
      case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT | RIDE_HAIL_TRANSIT => transitSpeedMeterPerSec
      case BIKE                                                       => bicycleSpeedMeterPerSec
      case _                                                          => walkSpeedMeterPerSec
    }
    val travelDistance: Int = Math.ceil(GeoUtils.minkowskiDistFormula(originUTM, destinationUTM)).toInt
    val travelTime: Int = Math
      .ceil(travelDistance / speed)
      .toInt + ((travelDistance / trafficSignalSpacing).toInt * waitingTimeAtAnIntersection).toInt
    (travelDistance, travelTime)
  }

  def getRideHailCost(
                               mode: BeamMode,
                               distanceInMeters: Double,
                               timeInSeconds: Double,
                               beamConfig: BeamConfig
                             ): Double = {
    mode match {
      case RIDE_HAIL =>
        beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMile * distanceInMeters / 1609.34 + beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMinute * timeInSeconds / 60 + beamConfig.beam.agentsim.agents.rideHail.defaultBaseCost
      case RIDE_HAIL_POOLED =>
        beamConfig.beam.agentsim.agents.rideHail.pooledCostPerMile * distanceInMeters / 1609.34 + beamConfig.beam.agentsim.agents.rideHail.pooledCostPerMinute * timeInSeconds / 60 + beamConfig.beam.agentsim.agents.rideHail.pooledBaseCost
      case _ =>
        0.0
    }
  }

  def getExcerptData(
                              timePeriodString: String,
                              hoursIncluded: List[Int],
                              origin: TAZ,
                              destination: TAZ,
                              mode: BeamMode,
                              dummyId: Id[BeamVehicleType],
                              skim: immutable.Map[ODSkimmerKey, ODSkimmerInternal],
                              beamServices: BeamServices
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
            beamServices
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

}
