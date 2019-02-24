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
class BeamSkimmer(services: Option[BeamServices] = None) {

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
    vehicleTypeId: org.matsim.api.core.v01.Id[BeamVehicleType]
  ): TimeDistanceAndCost = {

    val speed = mode match {
      case BeamMode.CAR     => carSpeedMeterPerSec
      case BeamMode.TRANSIT => transitSpeedMeterPerSec
      case BeamMode.BIKE    => bicycleSpeedMeterPerSec
      case _                => walkSpeedMeterPerSec
    }
    val travelDistance: Int = Math.ceil(GeoUtils.minkowskiDistFormula(origin, destination)).toInt
    val travelTime: Int = Math
      .ceil(travelDistance / speed)
      .toInt + ((travelDistance / trafficSignalSpacing).toInt * waitingTimeAtAnIntersection).toInt
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

    new TimeDistanceAndCost(new TimeAndCost(Some(travelTime), Some(travelCost)), Some(travelDistance))

  }
}

object BeamSkimmer {

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
