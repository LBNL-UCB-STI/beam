package beam.router
import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.router.BeamRouter.Location
import beam.router.BeamSkimmer.Skim
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{
  BIKE,
  CAR,
  CAV,
  DRIVE_TRANSIT,
  RIDE_HAIL,
  RIDE_HAIL_POOLED,
  RIDE_HAIL_TRANSIT,
  TRANSIT,
  WALK_TRANSIT
}
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamTrip}
import beam.sim.BeamServices
import beam.sim.common.GeoUtils
import com.google.inject.Inject
import org.matsim.api.core.v01.Id

import scala.collection.concurrent.TrieMap

//TODO to be validated against google api
class BeamSkimmer @Inject()() {
  // The OD/Mode/Time Matrix
  var skims: TrieMap[(Int, BeamMode, Id[TAZ], Id[TAZ]), Skim] = TrieMap()
  var modalAverage: TrieMap[BeamMode, Skim] = TrieMap()

  // 22.2 mph (9.924288 meter per second), is the average speed in cities
  //TODO better estimate can be drawn from city size
  // source: https://www.mitpressjournals.org/doi/abs/10.1162/rest_a_00744
  private val carSpeedMeterPerSec: Double = 9.924288
  // 12.1 mph (5.409184 meter per second), is average bus speed
  // source: https://www.apta.com/resources/statistics/Documents/FactBook/2017-APTA-Fact-Book.pdf
  // assuming for now that it includes the headway
  private val transitSpeedMeterPerSec: Double = 5.409184

  private val bicycleSpeedMeterPerSec: Double = 3

  // 3.1 mph -> 1.38 meter per second
  private val walkSpeedMeterPerSec: Double = 1.38

  // 940.6 Traffic Signal Spacing, Minor is 1,320 ft => 402.336 meters
  private val trafficSignalSpacing: Double = 402.336

  // average waiting time at an intersection is 17.25 seconds
  // source: https://pumas.nasa.gov/files/01_06_00_1.pdf
  private val waitingTimeAtAnIntersection: Double = 17.25

  def getTimeDistanceAndCost(
    origin: Location,
    destination: Location,
    departureTime: Int,
    mode: BeamMode,
    vehicleTypeId: org.matsim.api.core.v01.Id[BeamVehicleType],
    beamServicesOpt: Option[BeamServices] = None
  ): Skim = {
    beamServicesOpt match {
      case Some(beamServices) =>
        val origTaz = beamServices.tazTreeMap.getTAZ(origin.getX, origin.getY).tazId
        val destTaz = beamServices.tazTreeMap.getTAZ(origin.getX, origin.getY).tazId
        getSkimValue(departureTime, mode, origTaz, destTaz) match {
          case Some(skimValue) =>
            skimValue
          case None =>
            modalAverage.get(mode) match {
              case Some(foundSkim) =>
                foundSkim
              case None =>
                val (travelDistance, travelTime) = distanceAndTime(mode, origin, destination)
                val travelCost: Double = mode match {
                  case CAR | CAV =>
                    DrivingCost.estimateDrivingCost(
                      new BeamLeg(
                        departureTime,
                        mode,
                        travelTime,
                        new BeamPath(null, null, None, null, null, travelDistance)
                      ),
                      vehicleTypeId,
                      beamServices
                    )
                  case RIDE_HAIL =>
                    beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMile * travelDistance / 1609.0 + beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMinute * travelTime / 60.0
                  case RIDE_HAIL_POOLED =>
                    0.6 * (beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMile * travelDistance / 1609.0 + beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMinute * travelTime / 60.0)
                  case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT | RIDE_HAIL_TRANSIT => 0.25 * travelDistance / 1609
                  case _                                                          => 0.0
                }
                Skim(travelTime, travelDistance, travelCost, 0)
            }
        }
      case None =>
        modalAverage.get(mode) match {
          case Some(foundSkim) =>
            foundSkim
          case None =>
            val (travelDistance, travelTime) = distanceAndTime(mode, origin, destination)
            Skim(travelTime, travelDistance, 0.0, 0)
        }
    }
  }

  private def distanceAndTime(mode: BeamMode, origin: Location, destination: Location) = {
    val speed = mode match {
      case CAR | CAV | RIDE_HAIL                                      => carSpeedMeterPerSec
      case RIDE_HAIL_POOLED                                           => carSpeedMeterPerSec * 1.1
      case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT | RIDE_HAIL_TRANSIT => transitSpeedMeterPerSec
      case BIKE                                                       => bicycleSpeedMeterPerSec
      case _                                                          => walkSpeedMeterPerSec
    }
    val travelDistance: Int = Math.ceil(GeoUtils.minkowskiDistFormula(origin, destination)).toInt
    val travelTime: Int = Math
      .ceil(travelDistance / speed)
      .toInt + ((travelDistance / trafficSignalSpacing).toInt * waitingTimeAtAnIntersection).toInt
    (travelDistance, travelTime)
  }

  private def getSkimValue(time: Int, mode: BeamMode, orig: Id[TAZ], dest: Id[TAZ]): Option[Skim] = {
    skims.get((timeToBin(time), mode, orig, dest))
  }

  def observeTrip(trip: EmbodiedBeamTrip, beamServices: BeamServices) = {
    val origLeg = trip.legs.head.beamLeg
    val destLeg = trip.legs.last.beamLeg
    val timeBin = timeToBin(origLeg.startTime)
    val mode = trip.tripClassifier
    val origTaz = beamServices.tazTreeMap
      .getTAZ(origLeg.travelPath.startPoint.loc.getX, origLeg.travelPath.startPoint.loc.getY)
      .tazId
    val destTaz = beamServices.tazTreeMap
      .getTAZ(origLeg.travelPath.startPoint.loc.getX, origLeg.travelPath.startPoint.loc.getY)
      .tazId
    val key = (timeBin, mode, origTaz, destTaz)
    val payload =
      Skim(trip.totalTravelTimeInSecs.toDouble, trip.beamLegs().map(_.travelPath.distanceInM).sum, trip.costEstimate, 1)
    skims.get(key) match {
      case Some(existingSkim) =>
        val newPayload = Skim(
          mergeAverage(existingSkim.time, existingSkim.count, payload.time),
          mergeAverage(existingSkim.distance, existingSkim.count, payload.distance),
          mergeAverage(existingSkim.cost, existingSkim.count, payload.cost),
          existingSkim.count + 1
        )
        skims.put(key, newPayload)
      case None =>
        skims.put(key, payload)
    }
    modalAverage.get(mode) match {
      case Some(existingSkim) =>
        val newPayload = Skim(
          mergeAverage(existingSkim.time, existingSkim.count, payload.time),
          mergeAverage(existingSkim.distance, existingSkim.count, payload.distance),
          mergeAverage(existingSkim.cost, existingSkim.count, payload.cost),
          existingSkim.count + 1
        )
        modalAverage.put(mode, newPayload)
      case None =>
        modalAverage.put(mode, payload)
    }
  }
  def clear = {
    skims.clear()
    modalAverage.clear()
  }

  def timeToBin(departTime: Int) = {
    Math.floorMod(Math.floor(departTime.toDouble / 3600.0).toInt, 24)
  }

  def mergeAverage(existingAverage: Double, existingCount: Int, newValue: Double) =
    ((existingAverage * existingCount + newValue) / (existingCount + 1))
}

object BeamSkimmer {
  case class Skim(time: Double, distance: Double, cost: Double, count: Int)

  def main(args: Array[String]): Unit = {
    val config = org.matsim.core.config.ConfigUtils.createConfig()
    val sc: org.matsim.api.core.v01.Scenario = org.matsim.core.scenario.ScenarioUtils.createScenario(config)
    val skimmer = new BeamSkimmer()
    val output = skimmer.getTimeDistanceAndCost(
      new Location(0, 0),
      new Location(1600, 500),
      0,
      BeamMode.CAR,
      org.matsim.api.core.v01.Id.create[BeamVehicleType]("", classOf[BeamVehicleType])
    )
    println(output)
  }
}

//val householdBeamPlans = household.members.map(person => BeamPlan(person.getSelectedPlan)).toList
//val householdMatsimPlans = household.members.map(person => (person.getId -> person.getSelectedPlan)).toMap
//val fastSpeed = 50.0 * 1000.0 / 3600.0
//val medSpeed = 50.0 * 1000.0 / 3600.0
//val slowSpeed = 50.0 * 1000.0 / 3600.0
//val walkSpeed = 50.0 * 1000.0 / 3600.0
//val skim: Map[BeamMode, Double] = Map(
//CAV               -> fastSpeed,
//CAR               -> fastSpeed,
//WALK              -> walkSpeed,
//BIKE              -> slowSpeed,
//WALK_TRANSIT      -> medSpeed,
//DRIVE_TRANSIT     -> medSpeed,
//RIDE_HAIL         -> fastSpeed,
//RIDE_HAIL_POOLED  -> fastSpeed,
//RIDE_HAIL_TRANSIT -> medSpeed,
//TRANSIT           -> medSpeed
//)
