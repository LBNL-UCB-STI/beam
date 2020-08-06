package beam.utils.scenario.generic

import beam.sim.common.GeoUtils
import beam.utils.scenario.generic.readers.{CsvHouseholdInfoReader, CsvPersonInfoReader, CsvPlanElementReader}
import beam.utils.scenario.{HouseholdInfo, PersonInfo, PlanElement, ScenarioSource}
import org.matsim.api.core.v01.Coord

class GenericScenarioSource(
  val pathToHouseholds: String,
  val pathToPersonFile: String,
  val pathToPlans: String,
  val geoUtils: GeoUtils,
  val shouldConvertWgs2Utm: Boolean
) extends ScenarioSource {
  override def getPersons: Iterable[PersonInfo] = {
    CsvPersonInfoReader.read(pathToPersonFile)
  }

  override def getPlans: Iterable[PlanElement] = {
    CsvPlanElementReader.read(pathToPlans).map { plan: PlanElement =>
      if (plan.planElementType.equalsIgnoreCase("activity") && shouldConvertWgs2Utm) {
        val utmCoord = geoUtils.wgs2Utm(new Coord(plan.activityLocationX.get, plan.activityLocationY.get))
        plan.copy(activityLocationX = Some(utmCoord.getX), activityLocationY = Some(utmCoord.getY))
      } else {
        plan
      }
    }
  }

  override def getHousehold: Iterable[HouseholdInfo] = {
    CsvHouseholdInfoReader.read(pathToHouseholds).map { household =>
      if (shouldConvertWgs2Utm) {
        val utmCoord = geoUtils.wgs2Utm(new Coord(household.locationX, household.locationY))
        household.copy(locationX = utmCoord.getX, locationY = utmCoord.getY)
      } else {
        household
      }
    }
  }
}
