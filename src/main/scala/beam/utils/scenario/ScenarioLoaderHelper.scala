package beam.utils.scenario

import beam.utils.SnapCoordinateUtils
import beam.utils.SnapCoordinateUtils.{Category, CsvFile, Error, ErrorInfo, Result, SnapLocationHelper}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.{Activity, Leg, Person}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.scenario.MutableScenario
import org.matsim.households.{Household, HouseholdsFactoryImpl}

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.{collectionAsScalaIterableConverter, seqAsJavaListConverter}

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

    val people: List[Person] = scenario.getPopulation.getPersons.values().asScala.toList
    people.par.foreach { person =>
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

    outputDirMaybe.foreach { path =>
      if (planErrors.isEmpty) logger.info("No 'snap location' error to report for scenario plans.")
      else SnapCoordinateUtils.writeToCsv(s"$path/${CsvFile.Plans}", planErrors)
    }

    val validPeople: Set[Id[Person]] = scenario.getPopulation.getPersons.values().asScala.map(_.getId).toSet

    val households: List[Household] = scenario.getHouseholds.getHouseholds.values().asScala.toList
    households.par.foreach { household =>
      val members = household.getMemberIds.asScala.toSet
      val validMembers = validPeople.intersect(members)

      if (validMembers.isEmpty) {
        scenario.getHouseholds.getHouseholdAttributes.removeAllAttributes(household.getId.toString)
        scenario.getHouseholds.getHouseholds.remove(household.getId)
      } else if (validMembers != members) {
        val updatedHousehold = createHouseholdWithGivenMembers(household, validMembers.toList)
        scenario.getHouseholds.getHouseholds.replace(household.getId, updatedHousehold)
      }
    }

    val householdErrors: ListBuffer[ErrorInfo] = ListBuffer()

    val householdsWithMembers: List[Household] = scenario.getHouseholds.getHouseholds.values().asScala.toList
    householdsWithMembers.par.foreach { household =>
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
      if (householdErrors.isEmpty) logger.info("No 'snap location' error to report for scenario households.")
      else SnapCoordinateUtils.writeToCsv(s"$path/${CsvFile.Households}", householdErrors)
    }

  }

}
