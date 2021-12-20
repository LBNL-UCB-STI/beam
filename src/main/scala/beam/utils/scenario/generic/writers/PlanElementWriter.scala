package beam.utils.scenario.generic.writers

import beam.utils.csv.CsvWriter
import beam.utils.scenario.PlanElement

import scala.util.Try

trait PlanElementWriter {
  def write(path: String, xs: Iterable[PlanElement]): Unit
}

object CsvPlanElementWriter extends PlanElementWriter {

  private val headers: Array[String] = Array(
    "tripId",
    "personId",
    "planIndex",
    "planScore",
    "planSelected",
    "planElementType",
    "planElementIndex",
    "activityType",
    "activityLocationX",
    "activityLocationY",
    "activityEndTime",
    "legMode",
    "legDepartureTime",
    "legTravelTime",
    "legRouteType",
    "legRouteStartLink",
    "legRouteEndLink",
    "legRouteTravelTime",
    "legRouteDistance",
    "legRouteLinks",
    "geoId"
  )

  override def write(path: String, xs: Iterable[PlanElement]): Unit = {
    val csvWriter = new CsvWriter(path, headers)
    try {
      xs.foreach { planElement =>
        val legRouteLinks = planElement.legRouteLinks.mkString("|")
        csvWriter.write(
          planElement.tripId,
          planElement.personId.id,
          planElement.planIndex,
          planElement.planScore,
          planElement.planSelected,
          planElement.planElementType,
          planElement.planElementIndex,
          planElement.activityType,
          planElement.activityLocationX,
          planElement.activityLocationY,
          planElement.activityEndTime,
          planElement.legMode,
          planElement.legDepartureTime,
          planElement.legTravelTime,
          planElement.legRouteType,
          planElement.legRouteStartLink,
          planElement.legRouteEndLink,
          planElement.legRouteTravelTime,
          planElement.legRouteDistance,
          legRouteLinks,
          planElement.geoId.getOrElse("")
        )
      }
    } finally {
      Try(csvWriter.close())
    }

  }
}
