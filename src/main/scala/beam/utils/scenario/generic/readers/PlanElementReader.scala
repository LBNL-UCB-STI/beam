package beam.utils.scenario.generic.readers

import beam.utils.FileUtils
import beam.utils.csv.writers.ScenarioCsvWriter.ArrayItemSeparator
import beam.utils.scenario.{PersonId, PlanElement}
import org.matsim.api.core.v01.population.{Activity, Leg, Person, Plan}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.population.io.PopulationReader
import org.matsim.core.population.routes.NetworkRoute
import org.matsim.core.scenario.ScenarioUtils

import scala.jdk.CollectionConverters.collectionAsScalaIterableConverter
import scala.util.Try

trait PlanElementReader {
  def read(path: String): Array[PlanElement]
}

object CsvPlanElementReader extends PlanElementReader {
  import beam.utils.csv.GenericCsvReader._

  override def read(path: String): Array[PlanElement] = {
    val (it, toClose) = readAs[PlanElement](path, toPlanElement, x => true)
    try {
      it.toArray
    } finally {
      Try(toClose.close())
    }
  }

  private[readers] def toPlanElement(rec: java.util.Map[String, String]): PlanElement = {
    val personId = getIfNotNull(rec, "personId")
    val planIndex = getIfNotNull(rec, "planIndex").toInt
    val planElementType = getIfNotNull(rec, "planElementType")
    val planElementIndex = getIfNotNull(rec, "planElementIndex").toInt
    val activityType = Option(rec.get("activityType"))
    val linkIds =
      Option(rec.get("legRouteLinks")).map(_.split(ArrayItemSeparator).map(_.trim)).getOrElse(Array.empty[String])
    PlanElement(
      personId = PersonId(personId),
      planIndex = planIndex,
      planScore = getIfNotNull(rec, "planScore").toDouble,
      planSelected = getIfNotNull(rec, "planSelected").toBoolean,
      planElementType = planElementType,
      planElementIndex = planElementIndex,
      activityType = activityType,
      activityLocationX = Option(rec.get("activityLocationX")).map(_.toDouble),
      activityLocationY = Option(rec.get("activityLocationY")).map(_.toDouble),
      activityEndTime = Option(rec.get("activityEndTime")).map(_.toDouble),
      legMode = Option(rec.get("legMode")).map(_.toString),
      legDepartureTime = Option(rec.get("legDepartureTime")).map(_.toString),
      legTravelTime = Option(rec.get("legTravelTime")).map(_.toString),
      legRouteType = Option(rec.get("legRouteType")).map(_.toString),
      legRouteStartLink = Option(rec.get("legRouteStartLink")).map(_.toString),
      legRouteEndLink = Option(rec.get("legRouteEndLink")).map(_.toString),
      legRouteTravelTime = Option(rec.get("legRouteTravelTime")).map(_.toDouble),
      legRouteDistance = Option(rec.get("legRouteDistance")).map(_.toDouble),
      legRouteLinks = linkIds,
      geoId = Option(rec.get("geoId"))
    )
  }
}

object XmlPlanElementReader extends PlanElementReader {

  override def read(path: String): Array[PlanElement] = {
    val scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    new PopulationReader(scenario).parse(FileUtils.getInputStream(path))

    scenario.getPopulation.getPersons.values.asScala
      .flatMap { person =>
        person.getPlans.asScala.zipWithIndex.flatMap {
          case (plan, planIdx) =>
            plan.getPlanElements.asScala.zipWithIndex.map {
              case (planElement, planElementIdx) => (person, plan, planIdx, planElement, planElementIdx)
            }
        }
      }
      .collect {
        case (person, plan, planIdx, act: Activity, planElIdx) => toPlanElement(act, plan, planIdx, person, planElIdx)
        case (person, plan, planIdx, leg: Leg, planElIdx)      => toPlanElement(leg, plan, planIdx, person, planElIdx)
      }
      .toArray
  }

  private def toPlanElement(
    activity: Activity,
    plan: Plan,
    planIdx: Int,
    person: Person,
    planElementIdx: Int
  ): PlanElement =
    PlanElement(
      personId = PersonId(person.getId.toString),
      planIndex = planIdx,
      planScore = plan.getScore,
      planSelected = person.getSelectedPlan == plan,
      planElementType = "activity",
      planElementIndex = planElementIdx,
      activityType = Option(activity.getType),
      activityLocationX = Option(activity.getCoord).map(_.getX),
      activityLocationY = Option(activity.getCoord).map(_.getY),
      activityEndTime = Option(activity.getEndTime),
      legMode = None,
      legDepartureTime = None,
      legTravelTime = None,
      legRouteType = None,
      legRouteStartLink = None,
      legRouteEndLink = None,
      legRouteTravelTime = None,
      legRouteDistance = None,
      legRouteLinks = Seq.empty,
      geoId = None
    )

  private def toPlanElement(leg: Leg, plan: Plan, planIdx: Int, person: Person, planElementIdx: Int): PlanElement =
    PlanElement(
      personId = PersonId(person.getId.toString),
      planIndex = planIdx,
      planScore = plan.getScore,
      planSelected = person.getSelectedPlan == plan,
      planElementType = "leg",
      planElementIndex = planElementIdx,
      activityType = None,
      activityLocationX = None,
      activityLocationY = None,
      activityEndTime = None,
      legMode = Option(leg.getMode),
      legDepartureTime = Option(leg.getDepartureTime).map(_.toString),
      legTravelTime = Option(leg.getTravelTime).map(_.toString),
      legRouteType = Option(leg.getRoute).map(_.getRouteType),
      legRouteStartLink = Option(leg.getRoute).map(_.getStartLinkId.toString),
      legRouteEndLink = Option(leg.getRoute).map(_.getEndLinkId.toString),
      legRouteTravelTime = Option(leg.getRoute).map(_.getTravelTime),
      legRouteDistance = Option(leg.getRoute).map(_.getDistance),
      legRouteLinks = leg.getRoute match {
        case route: NetworkRoute => route.getLinkIds.asScala.map(_.toString).toSeq
        case _                   => Seq.empty
      },
      geoId = None
    )
}
