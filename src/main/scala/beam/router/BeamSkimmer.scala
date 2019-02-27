package beam.router
import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, BeamPath}
import beam.sim.BeamServices
import beam.sim.common.GeoUtils

case class TimeDistanceAndCost(timeAndCost: TimeAndCost, distance: Option[Int]) {
  override def toString =
    s"[time:${timeAndCost.time.getOrElse("NA")}][cost:${timeAndCost.cost.getOrElse("NA")}][distance:${distance.getOrElse("NA")}]"
}

//TODO to be validated against google api
class BeamSkimmer(services: Option[BeamServices] = None, scenario: org.matsim.api.core.v01.Scenario) {

  def getTimeDistanceAndCost(
                              origin: Location,
                              destination: Location,
                              departureTime: Int,
                              mode: BeamMode,
                              vehicleTypeId: org.matsim.api.core.v01.Id[BeamVehicleType]
                            ): TimeDistanceAndCost = {

    val travelDistance: Int = Math.ceil(GeoUtils.minkowskiDistFormula(origin, destination)).toInt
    val travelTime: Int = Math
      .ceil(travelDistance / BeamSkimmer.speedMeterPerSec(mode))
      .toInt + ((travelDistance / BeamSkimmer.trafficSignalSpacing).toInt * BeamSkimmer.waitingTimeAtAnIntersection).toInt
    val travelCost: Double = services
      .map(
        x =>
          DrivingCost.estimateDrivingCost(
            new BeamLeg(departureTime, mode, travelTime, new BeamPath(null, null, None, null, null, travelDistance)),
            vehicleTypeId,
            x
          )
      )
      .getOrElse(0)

    TimeDistanceAndCost(TimeAndCost(Some(travelTime), Some(travelCost)), Some(travelDistance))

  }
}

object BeamSkimmer {
  import beam.router.Modes.BeamMode.{
    BIKE,
    CAR,
    CAV,
    DRIVE_TRANSIT,
    RIDE_HAIL,
    RIDE_HAIL_POOLED,
    RIDE_HAIL_TRANSIT,
    TRANSIT,
    WALK,
    WALK_TRANSIT
  }

  // 22.2 mph (9.924288 meter per second), is the average speed in cities
  //TODO better estimate can be drawn from city size
  // source: https://www.mitpressjournals.org/doi/abs/10.1162/rest_a_00744
  private val carSpeedMeterPerSec: Double = 9.924288
  // 12.1 mph (5.409184 meter per second), is average bus speed
  // source: https://www.apta.com/resources/statistics/Documents/FactBook/2017-APTA-Fact-Book.pdf
  // assuming for now that it includes the headway
  private val transitSpeedMeterPerSec: Double = 5.409184

  // 9.6 mph -> 15.5 km/h
  private val bicycleSpeedMeterPerSec: Double = 4.305556

  // 3.1 mph -> 1.38 meter per second
  private val walkSpeedMeterPerSec: Double = 1.38

  // 940.6 Traffic Signal Spacing, Minor is 1,320 ft => 402.336 meters
  private val trafficSignalSpacing: Double = 402.336

  // average waiting time at an intersection is 17.25 seconds
  // source: https://pumas.nasa.gov/files/01_06_00_1.pdf
  private val waitingTimeAtAnIntersection: Double = 17.25

  //  val fastSpeed: Double = 50.0 * 1000.0 / 3600.0
  //  val medSpeed: Double = 50.0 * 1000.0 / 3600.0
  //  val slowSpeed: Double = 50.0 * 1000.0 / 3600.0
  //  val walkSpeed: Double = 50.0 * 1000.0 / 3600.0

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

  def main(args: Array[String]): Unit = {
    val config = org.matsim.core.config.ConfigUtils.createConfig()
    val sc: org.matsim.api.core.v01.Scenario = org.matsim.core.scenario.ScenarioUtils.createScenario(config)
    val skimmer = new BeamSkimmer(scenario = sc)
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
