package beam.utils.scenario

import beam.sim.BeamScenario
import beam.sim.common.GeoUtils
import beam.utils.SnapCoordinateUtils
import beam.utils.SnapCoordinateUtils.{Category, Error, ErrorInfo, Processed, Result, SnapLocationHelper}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Coord

import scala.collection.Iterable

trait ScenarioLoaderHelper extends LazyLogging {

  def beamScenario: BeamScenario
  def geo: GeoUtils
  def outputDirMaybe: Option[String]

  private val snapLocationHelper: SnapLocationHelper = SnapLocationHelper(
    geo,
    beamScenario.transportNetwork.streetLayer,
    beamScenario.beamConfig.beam.routing.r5.linkRadiusMeters
  )

  private def validatePersonPlans(personId: PersonId, plans: Iterable[PlanElement]): Processed[PlanElement] = {
    plans.foldLeft[Processed[PlanElement]](Processed()) {
      case (processed, planElement) if planElement.planElementType == PlanElement.Leg =>
        processed.copy(data = processed.data :+ planElement)
      case (processed, planElement) if planElement.planElementType == PlanElement.Activity =>
        val utmCoord = new Coord(planElement.activityLocationX.get, planElement.activityLocationY.get)
        snapLocationHelper.computeResult(utmCoord) match {
          case Result.Succeed(splitCoord) =>
            val updatedPlan = planElement
              .copy(activityLocationX = Some(splitCoord.getX), activityLocationY = Some(splitCoord.getY))
            processed.copy(data = processed.data :+ updatedPlan)
          case Result.OutOfBoundingBoxError =>
            processed.copy(errors =
              processed.errors :+ ErrorInfo(
                personId.id,
                Category.ScenarioPerson,
                Error.OutOfBoundingBox,
                utmCoord.getX,
                utmCoord.getY
              )
            )
          case Result.R5SplitNullError =>
            processed.copy(errors =
              processed.errors :+ ErrorInfo(
                personId.id,
                Category.ScenarioPerson,
                Error.R5SplitNull,
                utmCoord.getX,
                utmCoord.getY
              )
            )
        }
    }
  }

  private def snapLocationPlans(plans: Iterable[PlanElement]): Processed[PlanElement] = {
    plans.groupBy(_.personId).foldLeft[Processed[PlanElement]](Processed()) {
      case (processed, (personId, personPlans)) =>
        val Processed(updatedPlans, errors) = validatePersonPlans(personId, personPlans)
        if (updatedPlans.size == personPlans.size) {
          processed.copy(data = processed.data ++ updatedPlans, errors = processed.errors ++ errors)
        } else {
          processed.copy(errors = processed.errors ++ errors)
        }
    }
  }

  private def snapLocationHouseholds(households: Iterable[HouseholdInfo]): Processed[HouseholdInfo] = {
    households.foldLeft[Processed[HouseholdInfo]](Processed()) { case (processed, household) =>
      val householdId = household.householdId.id
      val utmCoord = new Coord(household.locationX, household.locationY)
      snapLocationHelper.computeResult(utmCoord) match {
        case Result.Succeed(splitCoord) =>
          val updatedHousehold = household.copy(locationX = splitCoord.getX, locationY = splitCoord.getY)
          processed.copy(data = processed.data :+ updatedHousehold)
        case Result.OutOfBoundingBoxError =>
          processed.copy(errors =
            processed.errors :+ ErrorInfo(
              householdId,
              Category.ScenarioHousehold,
              Error.OutOfBoundingBox,
              utmCoord.getX,
              utmCoord.getY
            )
          )
        case Result.R5SplitNullError =>
          processed.copy(errors =
            processed.errors :+ ErrorInfo(
              householdId,
              Category.ScenarioHousehold,
              Error.R5SplitNull,
              utmCoord.getX,
              utmCoord.getY
            )
          )
      }
    }
  }

  protected def getPersonsWithPlan(
    persons: Iterable[PersonInfo],
    plans: Iterable[PlanElement],
    households: Iterable[HouseholdInfo]
  ): Iterable[PersonInfo] = {
    val personIdsWithPlan = plans.map(_.personId).toSet
    val householdIds = households.map(_.householdId).toSet
    persons.filter(person => personIdsWithPlan.contains(person.personId) && householdIds.contains(person.householdId))
  }

  protected def validatePlans(plans: Iterable[PlanElement]): Iterable[PlanElement] = {
    val Processed(validPlans, errors) = snapLocationPlans(plans)

    outputDirMaybe.foreach { path =>
      if (errors.isEmpty) logger.info("No 'snap location' error to report for scenario plans.")
      else SnapCoordinateUtils.writeToCsv(s"$path/snapLocationPlanErrors.csv.gz", errors)
    }

    val filteredCnt = plans.size - validPlans.size
    if (filteredCnt > 0) {
      logger.info(s"Filtered out $filteredCnt plans. Total number of plans: ${validPlans.size}")
    }
    validPlans
  }

  protected def validateHouseholds(households: Iterable[HouseholdInfo]): Iterable[HouseholdInfo] = {
    val Processed(validHouseholds, errors) = snapLocationHouseholds(households)

    outputDirMaybe.foreach { path =>
      if (errors.isEmpty) logger.info("No 'snap location' error to report for scenario households.")
      else SnapCoordinateUtils.writeToCsv(s"$path/snapLocationHouseholdErrors.csv.gz", errors)
    }

    val filteredCnt = households.size - validHouseholds.size
    if (filteredCnt > 0) {
      logger.info(s"Filtered out $filteredCnt plans. Total number of plans: ${validHouseholds.size}")
    }
    validHouseholds
  }

}
