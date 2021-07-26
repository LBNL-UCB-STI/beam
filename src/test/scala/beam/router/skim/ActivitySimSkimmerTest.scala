package beam.router.skim

import beam.router.skim.ActivitySimSkimmer.ExcerptData
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * @author Dmitry Openkov
  */
class ActivitySimSkimmerTest extends AnyFlatSpec with Matchers {
  "ActivitySimSkimmer" must "produce correct CSV rows" in {
    ExcerptData(
      timePeriodString = ActivitySimTimeBin.MIDDAY.toString,
      pathType = ActivitySimPathType.WALK,
      originId = "origin-1",
      destinationId = "destination-1",
      weightedGeneralizedTime = 1.0,
      weightedTotalInVehicleTime = 2.0,
      weightedGeneralizedCost = 3.0,
      weightedDistance = 4.0,
      weightedWalkAccess = 5.0,
      weightedWalkAuxiliary = 6.0,
      weightedWalkEgress = 7.0,
      weightedDriveTimeInMinutes = 8.0,
      weightedDriveDistanceInMeters = 9.0,
      weightedLightRailInVehicleTimeInMinutes = 10.0,
      weightedFerryInVehicleTimeInMinutes = 11.0,
      weightedTransitBoardingsCount = 12.0,
      weightedCost = 13.0,
      debugText = "debug-text"
    ).toCsvString should be(
      "MD,WALK,origin-1,destination-1,1.0,2.0,3.0,4.0,5.0,6.0,7.0,8.0,9.0,10.0,11.0,12.0,13.0,debug-text\n"
    )
  }
}
