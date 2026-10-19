package beam.utils.scenario.generic.readers

import beam.utils.FileUtils
import beam.utils.csv.writers.ScenarioCsvWriter.ArrayItemSeparator
import beam.utils.scenario.{PersonId, PlanElement}
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.population.{Activity, Leg, Person, Plan}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.population.io.PopulationReader
import org.matsim.core.population.routes.NetworkRoute
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.utils.objectattributes.attributable.Attributable
import beam.utils.OptionalUtils.OptionalTimeExtension

import java.io.Closeable
import scala.jdk.CollectionConverters.collectionAsScalaIterableConverter
import scala.util.Try

trait PlanElementReader {
  def read(path: String): Array[PlanElement]

  def read(path: String, maybeItem: Option[Attributable]): Array[PlanElement]

  def readWithFilter(path: String, filter: PlanElement => Boolean): (Iterator[PlanElement], Closeable)
}

object CsvPlanElementReader extends PlanElementReader {
  import beam.utils.csv.GenericCsvReader._

  override def read(path: String): Array[PlanElement] = {
    val (it: Iterator[PlanElement], toClose) = readAs[PlanElement](path, toPlanElement, _ => true)
    try {
      it.toArray
    } finally {
      Try(toClose.close())
    }
  }

  override def read(path: String, maybeItem: Option[Attributable]): Array[PlanElement] = {
    throw new NotImplementedError()
  }

  override def readWithFilter(path: String, filter: PlanElement => Boolean): (Iterator[PlanElement], Closeable) = {
    readAs[PlanElement](path, toPlanElement, filter)
  }

  private def parseDouble(s: String): Option[Double] = {
    Option(s)
      .map(_.trim)
      .filter(v => v.nonEmpty && !v.equalsIgnoreCase("null") && !v.equalsIgnoreCase("none"))
      .flatMap { v =>
        if (v.equalsIgnoreCase("nan")) Some(Double.NaN)
        else Try(v.toDouble).toOption
      }
  }

  private def parseString(s: String): Option[String] = {
    Option(s).map(_.trim).filter(v => v.nonEmpty && !v.equalsIgnoreCase("null") && !v.equalsIgnoreCase("none"))
  }

  private def parseBoolean(s: String): Option[Boolean] = {
    Option(s).map(_.trim.toLowerCase).flatMap {
      case "true" | "t" | "1" | "1.0"  => Some(true)
      case "false" | "f" | "0" | "0.0" => Some(false)
      case other                       => Try(other.toBoolean).toOption
    }
  }

  private[readers] def toPlanElement(rec: java.util.Map[String, String]): PlanElement = {

    val personId = getIfNotNull(rec, "personId")
    val planIndex = parseDouble(rec.get("planIndex")).map(_.toInt).getOrElse(0)
    val planElementType = getIfNotNull(rec, "planElementType")
    val planElementIndex = parseDouble(rec.get("planElementIndex")).map(_.toInt).getOrElse(0)
    val activityType = parseString(rec.get("activityType"))
    val linkIds =
      Option(rec.get("legRouteLinks")).map(_.split(ArrayItemSeparator).map(_.trim)).getOrElse(Array.empty[String])
    PlanElement(
      tripId = Option(rec.get("tripId"))
        .orElse(Option(rec.get("trip_id")))
        .map(_.trim)
        .filterNot(s => s.isEmpty || s.equalsIgnoreCase("null"))
        .getOrElse(""),
      tourId = Option(rec.get("tourId"))
        .orElse(Option(rec.get("tour_id")))
        .map(_.trim)
        .filterNot(s => s.isEmpty || s.equalsIgnoreCase("null"))
        .getOrElse(""),
      personId = PersonId(personId),
      planIndex = planIndex,
      planScore = parseDouble(rec.get("planScore")).getOrElse(Double.NaN),
      planSelected = parseBoolean(rec.get("planSelected")).getOrElse(true),
      planElementType = PlanElement.PlanElementType(planElementType),
      planElementIndex = planElementIndex,
      activityType = activityType,
      activityLocationX = parseDouble(rec.get("activityLocationX")),
      activityLocationY = parseDouble(rec.get("activityLocationY")),
      activityEndTime = parseDouble(rec.get("activityEndTime")),
      legMode = parseString(rec.get("legMode")),
      legDepartureTime = parseString(rec.get("legDepartureTime")),
      legTravelTime = parseString(rec.get("legTravelTime")),
      legExpectedTravelTime = parseDouble(rec.get("trip_dur_min")),
      legExpectedCost = parseDouble(rec.get("trip_cost_dollars")),
      legRouteType = parseString(rec.get("legRouteType")),
      legRouteStartLink = parseString(rec.get("legRouteStartLink")),
      legRouteEndLink = parseString(rec.get("legRouteEndLink")),
      legRouteTravelTime = parseDouble(rec.get("legRouteTravelTime")),
      legRouteDistance = parseDouble(rec.get("legRouteDistance")),
      legRouteLinks = linkIds,
      geoId = parseString(rec.get("geoId"))
    )
  }
}

object XmlPlanElementReader extends PlanElementReader {

  override def read(path: String): Array[PlanElement] = {
    val scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    new PopulationReader(scenario).parse(FileUtils.getInputStream(path))

    scenario.getPopulation.getPersons.values.asScala
      .flatMap { person =>
        person.getPlans.asScala.zipWithIndex.flatMap { case (plan, planIdx) =>
          plan.getPlanElements.asScala.zipWithIndex.map { case (planElement, planElementIdx) =>
            (person, plan, planIdx, planElement, planElementIdx)
          }
        }
      }
      .collect {
        case (person, plan, planIdx, act: Activity, planElIdx) if act != null =>
          toPlanElement(act, plan, planIdx, person, planElIdx)
        case (person, plan, planIdx, leg: Leg, planElIdx) if leg != null =>
          toPlanElement(leg, plan, planIdx, person, planElIdx)
      }
      .toArray
  }

  override def read(path: String, maybeItem: Option[Attributable] = None): Array[PlanElement] = {
    val maybeNetwork = maybeItem match {
      case Some(network: Network) => Some(network)
      case _                      => None
    }

    val scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    new PopulationReader(scenario).parse(FileUtils.getInputStream(path))

    scenario.getPopulation.getPersons.values.asScala
      .flatMap { person =>
        person.getPlans.asScala.zipWithIndex.flatMap { case (plan, planIdx) =>
          plan.getPlanElements.asScala.zipWithIndex.map { case (planElement, planElementIdx) =>
            (person, plan, planIdx, planElement, planElementIdx)
          }
        }
      }
      .collect {
        case (person, plan, planIdx, act: Activity, planElIdx) =>
          val maybeCoord =
            Option(act.getCoord).orElse(maybeNetwork.map(net => net.getLinks.get(act.getLinkId)).map(_.getCoord))
          toPlanElement(act, plan, planIdx, person, planElIdx, maybeCoord)
        case (person, plan, planIdx, leg: Leg, planElIdx) => toPlanElement(leg, plan, planIdx, person, planElIdx)
      }
      .toArray
  }

  override def readWithFilter(path: String, filter: PlanElement => Boolean): (Iterator[PlanElement], Closeable) = {
    throw new NotImplementedError()
//    readAs[PlanElement](path, toPlanElement, filter)
  }

  private def toPlanElement(
    activity: Activity,
    plan: Plan,
    planIdx: Int,
    person: Person,
    planElementIdx: Int,
    maybeCoord: Option[Coord] = None
  ): PlanElement = {
    PlanElement(
      tripId = Option(activity.getAttributes.getAttribute("trip_id"))
        .map(_.toString.trim)
        .filterNot(s => s.isEmpty || s.equalsIgnoreCase("null"))
        .getOrElse(""),
      tourId = Option(activity.getAttributes.getAttribute("tour_id"))
        .map(_.toString.trim)
        .filterNot(s => s.isEmpty || s.equalsIgnoreCase("null"))
        .getOrElse(""),
      personId = PersonId(person.getId.toString),
      planIndex = planIdx,
      planScore = Option(plan.getScore).map(_.toDouble).getOrElse(Double.NaN),
      planSelected = person.getSelectedPlan == plan,
      planElementType = PlanElement.Activity,
      planElementIndex = planElementIdx,
      activityType = Option(activity.getType),
      activityLocationX = maybeCoord.map(_.getX),
      activityLocationY = maybeCoord.map(_.getY),
      activityEndTime = activity.getEndTime.toOption,
      legMode = None,
      legDepartureTime = None,
      legTravelTime = None,
      legExpectedTravelTime = None,
      legExpectedCost = None,
      legRouteType = None,
      legRouteStartLink = None,
      legRouteEndLink = None,
      legRouteTravelTime = None,
      legRouteDistance = None,
      legRouteLinks = Seq.empty,
      geoId = None
    )
  }

  private def toPlanElement(leg: Leg, plan: Plan, planIdx: Int, person: Person, planElementIdx: Int): PlanElement =
    PlanElement(
      tripId = Option(leg.getAttributes.getAttribute("trip_id"))
        .map(_.toString.trim)
        .filterNot(s => s.isEmpty || s.equalsIgnoreCase("null"))
        .getOrElse(""),
      tourId = Option(leg.getAttributes.getAttribute("tour_id"))
        .map(_.toString.trim)
        .filterNot(s => s.isEmpty || s.equalsIgnoreCase("null"))
        .getOrElse(""),
      personId = PersonId(person.getId.toString),
      planIndex = planIdx,
      planScore = Option(plan.getScore).map(_.toDouble).getOrElse(Double.NaN),
      planSelected = person.getSelectedPlan == plan,
      planElementType = PlanElement.Leg,
      planElementIndex = planElementIdx,
      activityType = None,
      activityLocationX = None,
      activityLocationY = None,
      activityEndTime = None,
      legMode = Option(leg).map(_.getMode),
      legDepartureTime = leg.getDepartureTime.toOption.map(_.toString),
      legTravelTime = leg.getTravelTime.toOption.map(_.toString),
      legExpectedTravelTime = Option(leg.getAttributes.getAttribute("trip_dur_min"))
        .filter(_.toString.nonEmpty)
        .map(_.toString)
        .flatMap(s => Try(s.toDouble).toOption),
      legExpectedCost = Option(leg.getAttributes.getAttribute("trip_cost_dollars"))
        .filter(_.toString.nonEmpty)
        .map(_.toString)
        .flatMap(s => Try(s.toDouble).toOption),
      legRouteType = Option(leg.getRoute).map(_.getRouteType),
      legRouteStartLink = Option(leg.getRoute).map(_.getStartLinkId.toString),
      legRouteEndLink = Option(leg.getRoute).map(_.getEndLinkId.toString),
      legRouteTravelTime = Option(leg.getRoute).flatMap(_.getTravelTime.toOption),
      legRouteDistance = Option(leg.getRoute).map(_.getDistance),
      legRouteLinks = leg.getRoute match {
        case route: NetworkRoute => route.getLinkIds.asScala.map(_.toString).toSeq
        case _                   => Seq.empty
      },
      geoId = None
    )
}
