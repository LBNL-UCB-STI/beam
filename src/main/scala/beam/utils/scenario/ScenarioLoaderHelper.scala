package beam.utils.scenario

import beam.sim.BeamScenario
import beam.sim.common.GeoUtils
import beam.utils.SnapCoordinateUtils
import beam.utils.SnapCoordinateUtils.{Category, CsvFile, Error, ErrorInfo, Processed, Result, SnapLocationHelper}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.population.{Activity, Leg, Person}
import org.matsim.core.scenario.MutableScenario
import org.matsim.households.{Household, HouseholdsFactoryImpl}

import scala.collection.Iterable
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.{
  collectionAsScalaIterableConverter,
  seqAsJavaListConverter,
  setAsJavaSetConverter
}

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
      else SnapCoordinateUtils.writeToCsv(s"$path/${CsvFile.Plans}", errors)
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
      else SnapCoordinateUtils.writeToCsv(s"$path/${CsvFile.Households}", errors)
    }

    val filteredCnt = households.size - validHouseholds.size
    if (filteredCnt > 0) {
      logger.info(s"Filtered out $filteredCnt plans. Total number of plans: ${validHouseholds.size}")
    }
    validHouseholds
  }

}

object ScenarioLoaderHelper extends LazyLogging {

  import org.matsim.api.core.v01.population.PlanElement

  private def createHouseholdWithGivenMembers(
    household: Household,
    members: List[Id[Person]]
  ): Household = {
    val householdResult = new HouseholdsFactoryImpl().createHousehold(household.getId)
    householdResult.setIncome(household.getIncome)
    householdResult.setVehicleIds(household.getVehicleIds)
    householdResult.setMemberIds(members.asJava)
    householdResult
  }

  private def updatePlanElementCoord(
    personId: Id[Person],
    elements: Vector[PlanElement],
    snapLocationHelper: SnapLocationHelper
  ): Vector[ErrorInfo] = {
    val errors = elements.foldLeft[Vector[ErrorInfo]](Vector.empty) { (errors, element) =>
      element match {
        case _: Leg => errors
        case a: Activity =>
          val planCoord = a.getCoord
          snapLocationHelper.computeResult(planCoord) match {
            case Result.Succeed(_) =>
              // note: we don't want to update coord in-place here since we might end up removing plan from the person
              errors
            case Result.OutOfBoundingBoxError =>
              errors :+ ErrorInfo(
                personId.toString,
                Category.ScenarioPerson,
                Error.OutOfBoundingBox,
                planCoord.getX,
                planCoord.getY
              )
            case Result.R5SplitNullError =>
              errors :+ ErrorInfo(
                personId.toString,
                Category.ScenarioPerson,
                Error.R5SplitNull,
                planCoord.getX,
                planCoord.getY
              )
          }
      }
    }

    if (errors.isEmpty) {
      elements.foreach {
        case a: Activity =>
          val planCoord = a.getCoord
          snapLocationHelper.find(planCoord) match {
            case Some(coord) =>
              a.setCoord(coord)
            case None =>
              logger.error("UNEXPECTED: there should be mapped split location for {} coord.", planCoord)
          }
        case _ =>
      }
    }

    errors
  }

  def validateScenario(
    scenario: MutableScenario,
    snapLocationHelper: SnapLocationHelper,
    outputDirMaybe: Option[String] = None
  ): Unit = {
    val planErrors: ListBuffer[ErrorInfo] = ListBuffer()
    val householdErrors: ListBuffer[ErrorInfo] = ListBuffer()
    val people: List[Person] = scenario.getPopulation.getPersons.values().asScala.toList
    people.foreach { person =>
      val plans = person.getPlans.asScala.toList
      plans.foreach { plan =>
        val elements: Vector[PlanElement] = plan.getPlanElements.asScala.toVector
        val errors: Vector[ErrorInfo] = updatePlanElementCoord(person.getId, elements, snapLocationHelper)
        if (errors.nonEmpty) {
          planErrors.appendAll(errors)
          person.removePlan(plan)
        }
      }
      val planCount = person.getPlans.size()
      if (planCount == 0) {
        scenario.getPopulation.removePerson(person.getId)
      }
    }

    val validPeople: Set[Id[Person]] = scenario.getPopulation.getPersons.values().asScala.map(_.getId).toSet

    val households: List[Household] = scenario.getHouseholds.getHouseholds.values().asScala.toList
    households.foreach { household =>
      val members = household.getMemberIds.asScala.toSet
      val validMembers = validPeople.intersect(members)

      if (validMembers.isEmpty) {
        scenario.getHouseholds.getHouseholds.remove(household.getId)
        scenario.getHouseholds.getHouseholdAttributes.removeAllAttributes(household.getId.toString)
      } else {
        val updatedHousehold = createHouseholdWithGivenMembers(household, validMembers.toList)
        scenario.getHouseholds.getHouseholds.replace(household.getId, updatedHousehold)
      }
    }

    val householdsWithMembers: List[Household] = scenario.getHouseholds.getHouseholds.values().asScala.toList
    householdsWithMembers.foreach { household =>
      val householdId = household.getId.toString
      val attr = scenario.getHouseholds.getHouseholdAttributes
      val locationX = attr.getAttribute(householdId, "homecoordx").asInstanceOf[Double]
      val locationY = attr.getAttribute(householdId, "homecoordy").asInstanceOf[Double]
      val planCoord = new Coord(locationX, locationY)

      snapLocationHelper.computeResult(planCoord) match {
        case Result.Succeed(splitCoord) =>
          attr.putAttribute(householdId, "homecoordx", splitCoord.getX)
          attr.putAttribute(householdId, "homecoordy", splitCoord.getY)
        case Result.OutOfBoundingBoxError =>
          household.getMemberIds.asScala.toList.foreach(personId => scenario.getPopulation.getPersons.remove(personId))
          scenario.getHouseholds.getHouseholds.remove(household.getId)
          householdErrors.append(
            ErrorInfo(
              householdId,
              Category.ScenarioHousehold,
              Error.OutOfBoundingBox,
              planCoord.getX,
              planCoord.getY
            )
          )
        case Result.R5SplitNullError =>
          household.getMemberIds.asScala.toList.foreach(personId => scenario.getPopulation.getPersons.remove(personId))
          scenario.getHouseholds.getHouseholds.remove(household.getId)
          householdErrors.append(
            ErrorInfo(
              householdId,
              Category.ScenarioHousehold,
              Error.R5SplitNull,
              planCoord.getX,
              planCoord.getY
            )
          )
      }
    }

    outputDirMaybe.foreach { path =>
      if (planErrors.isEmpty) logger.info("No 'snap location' error to report for scenario plans.")
      else SnapCoordinateUtils.writeToCsv(s"$path/${CsvFile.Plans}", planErrors)

      if (householdErrors.isEmpty) logger.info("No 'snap location' error to report for scenario households.")
      else SnapCoordinateUtils.writeToCsv(s"$path/${CsvFile.Households}", householdErrors)
    }
  }

}
