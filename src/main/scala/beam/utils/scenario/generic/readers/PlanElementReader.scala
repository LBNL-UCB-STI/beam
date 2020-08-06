package beam.utils.scenario.generic.readers

import beam.utils.scenario.{PersonId, PlanElement}

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
    val linkIds = Option(rec.get("legRouteLinks")).map(_.split("\\|").map(_.trim)).getOrElse(Array.empty[String])
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
