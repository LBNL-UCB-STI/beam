package beam.analysis.cartraveltime

import beam.agentsim.agents.vehicles.{BeamVehicleType, FuelType, VehicleCategory}
import beam.agentsim.events.{PathTraversalEvent, SpaceTime}
import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, BeamPath}
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig.Beam.Calibration.StudyArea
import org.matsim.api.core.v01.Id
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class StudyAreaTripFilterTest extends AnyFunSuite with Matchers {
  // Texas capitol as center of study area: https://g.page/TexasCapitol?share
  // The bounding box looks like the following: https://imgur.com/a/GLKgRyl
  private val studyArea = StudyArea(enabled = true, lat = 30.2746698, lon = -97.7425392, radius = 10000)
  private val geoUtils: GeoUtils = new GeoUtils {
    override def localCRS: String = "epsg:26910"
  }
  private val studyAreaTripFilter = new StudyAreaTripFilter(studyArea, geoUtils)
  private val vehicleType = BeamVehicleType(
    id = Id.create("car", classOf[BeamVehicleType]),
    seatingCapacity = 1,
    standingRoomCapacity = 1,
    lengthInMeter = 3,
    primaryFuelType = FuelType.Gasoline,
    primaryFuelConsumptionInJoulePerMeter = 0.1,
    primaryFuelCapacityInJoule = 0.1,
    vehicleCategory = VehicleCategory.Car
  )
  private val beamLeg = BeamLeg(
    startTime = 0,
    mode = BeamMode.CAR,
    duration = 25,
    travelPath = BeamPath(
      linkIds = Vector(1, 2, 3, 4, 5),
      linkTravelTime = Vector(5, 5, 5, 5, 5),
      transitStops = None,
      startPoint = SpaceTime.zero,
      endPoint = SpaceTime.zero.copy(time = 20),
      distanceInM = 10.0
    )
  )
  private val defaultPte = PathTraversalEvent(
    time = 0,
    vehicleId = Id.createVehicleId(1),
    driverId = "driver_id",
    vehicleType = vehicleType,
    numPass = 1,
    beamLeg = beamLeg,
    primaryFuelConsumed = 1.0,
    secondaryFuelConsumed = 0.0,
    endLegPrimaryFuelLevel = 1.0,
    endLegSecondaryFuelLevel = 0.0,
    amountPaid = 0,
    Vector.empty
  )

  test("Should recognize that PTE is inside study area if both start and end are inside of study area") {
    val pteInsideStudyArea =
      defaultPte.copy(startX = -97.763074, startY = 30.235920, endX = -97.687817, endY = 30.303643)
    studyAreaTripFilter.considerPathTraversal(pteInsideStudyArea) shouldBe true
  }
  test("Should recognize that PTE is outside of study area if only start is outside of study area") {
    val pte = defaultPte.copy(startX = -97.792733, startY = 30.177015, endX = -97.687817, endY = 30.303643)
    studyAreaTripFilter.considerPathTraversal(pte) shouldBe false
  }

  test("Should recognize that PTE is outside of study area if only end is outside of study area") {
    val pte = defaultPte.copy(startX = -97.763074, startY = 30.235920, endX = -97.661009, endY = 30.372633)
    studyAreaTripFilter.considerPathTraversal(pte) shouldBe false
  }

  test("Should recognize that PTE is outside of study area if both start and end are outside of study area") {
    val pte = defaultPte.copy(startX = -97.792733, startY = 30.177015, endX = -97.661009, endY = 30.372633)
    studyAreaTripFilter.considerPathTraversal(pte) shouldBe false
  }
}
