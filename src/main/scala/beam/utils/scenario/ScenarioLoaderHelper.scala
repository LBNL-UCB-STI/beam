package beam.utils.scenario

import beam.sim.BeamScenario
import beam.sim.common.GeoUtilsImpl
import beam.utils.SnapCoordinateUtils
import beam.utils.SnapCoordinateUtils.{Category, CsvFile, ErrorInfo, SnapLocationHelper}
import beam.utils.logging.ExponentialLazyLogging
import org.matsim.api.core.v01.population.{Activity, Leg, Person}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.scenario.MutableScenario
import org.matsim.households.{Household, HouseholdUtils, HouseholdsFactoryImpl}

import scala.collection.compat.IterableFactoryExtensionMethods
import scala.collection.immutable.HashSet
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.{
  collectionAsScalaIterableConverter,
  mapAsScalaMapConverter,
  seqAsJavaListConverter
}

object ScenarioLoaderHelper extends ExponentialLazyLogging {

  import org.matsim.api.core.v01.population.PlanElement

  private def createHouseholdWithGivenMembers(
    household: Household,
    members: List[Id[Person]]
  ): Household = {
    val householdResult = new HouseholdsFactoryImpl().createHousehold(household.getId)
    householdResult.setIncome(household.getIncome)
    householdResult.setVehicleIds(household.getVehicleIds)
    householdResult.setMemberIds(members.asJava)
    val originHouseAttributes = household.getAttributes.getAsMap.asScala
    originHouseAttributes.map { case (key, value) => HouseholdUtils.putHouseholdAttribute(householdResult, key, value) }
    householdResult
  }

  private def updatePlanElementCoord(
    personId: Id[Person],
    elements: Vector[PlanElement],
    snapLocationHelper: SnapLocationHelper,
    convertWgs2Utm: Boolean
  ): Vector[ErrorInfo] = {
    val errors = elements.foldLeft[Vector[ErrorInfo]](Vector.empty) { (errors, element) =>
      element match {
        case _: Leg => errors
        case a: Activity =>
          val planCoord = a.getCoord
          snapLocationHelper.computeResult(planCoord) match {
            case Right(_) =>
              // note: we don't want to update coord in-place here since we might end up removing plan from the person
              errors
            case Left(error) =>
              errors :+ ErrorInfo(
                personId.toString,
                Category.ScenarioPerson,
                error,
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
          snapLocationHelper.find(planCoord, !convertWgs2Utm) match {
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
    beamScenario: BeamScenario,
    outputDirMaybe: Option[String] = None
  ): SnapLocationHelper = {

    val snapLocationHelper = SnapLocationHelper(
      new GeoUtilsImpl(beamScenario.beamConfig),
      beamScenario.transportNetwork.streetLayer,
      beamScenario.beamConfig.beam.routing.r5.linkRadiusMeters
    )

    val planErrors: ListBuffer[ErrorInfo] = ListBuffer()

    val people: List[Person] = scenario.getPopulation.getPersons.values().asScala.toList
    people.par.foreach { person =>
      val plans = person.getPlans.asScala.toList
      plans.foreach { plan =>
        val elements: Vector[PlanElement] = plan.getPlanElements.asScala.toVector
        val errors: Vector[ErrorInfo] = updatePlanElementCoord(
          person.getId,
          elements,
          snapLocationHelper,
          beamScenario.beamConfig.beam.exchange.scenario.convertWgs2Utm
        )
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

    val validPeople: HashSet[Id[Person]] = HashSet.from(scenario.getPopulation.getPersons.values().asScala.map(_.getId))

    val households: List[Household] = scenario.getHouseholds.getHouseholds.values().asScala.toList
    households.par.foreach { household =>
      val members = household.getMemberIds.asScala.toSet
      val validMembers = members.filter(validPeople)

      if (validMembers.isEmpty) {
        scenario.getHouseholds.getHouseholds.get(household.getId).getAttributes.clear()
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
      val locationX = HouseholdUtils.getHouseholdAttribute(household, "homecoordx").asInstanceOf[Double]
      val locationY = HouseholdUtils.getHouseholdAttribute(household, "homecoordy").asInstanceOf[Double]
      val planCoord = new Coord(locationX, locationY)

      snapLocationHelper.computeResult(planCoord) match {
        case Right(splitCoord) =>
          HouseholdUtils.putHouseholdAttribute(household, "homecoordx", splitCoord.getX)
          HouseholdUtils.putHouseholdAttribute(household, "homecoordy", splitCoord.getY)
        case Left(error) =>
          household.getMemberIds.asScala.toList.foreach(personId => scenario.getPopulation.getPersons.remove(personId))
          scenario.getHouseholds.getHouseholds.remove(household.getId)
          householdErrors.append(
            ErrorInfo(
              householdId,
              Category.ScenarioHousehold,
              error,
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

    snapLocationHelper
  }

}
