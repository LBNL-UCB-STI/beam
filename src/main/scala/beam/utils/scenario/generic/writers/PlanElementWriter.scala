package beam.utils.scenario.generic.writers

import beam.utils.csv.CsvWriter
import beam.utils.scenario.PlanElement

import scala.util.Try

trait PlanElementWriter {
  def write(path: String, xs: Iterable[PlanElement]): Unit
}

class CsvPlanElementWriter(val path: String) extends AutoCloseable {
  import CsvPlanElementWriter._
  private val csvWriter = new CsvWriter(path, headers)

  def write(xs: Iterable[PlanElement]): Unit = {
    writeTo(xs, csvWriter)
  }
  override def close(): Unit = {
    Try(csvWriter.close())
  }
}

object CsvPlanElementWriter extends PlanElementWriter {
  private val headers: Array[String] = Array(
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
    val csvWriter: CsvWriter = new CsvWriter(path, headers)
    try {
      writeTo(xs, csvWriter)
    } finally {
      Try(csvWriter.close())
    }

  }

  private def writeTo(xs: Iterable[PlanElement], csvWriter: CsvWriter): Unit = {
    xs.foreach { planElement =>
      val legRouteLinks = planElement.legRouteLinks.mkString("|")
      csvWriter.write(
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
  }
}
