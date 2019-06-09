package beam.utils.map

import java.io.{BufferedWriter, Closeable, Writer}
import java.util.concurrent.TimeUnit

import beam.agentsim.events.PathTraversalEvent
import beam.sim.common.GeoUtils
import beam.utils.scenario.{PersonId, PlanElement}
import beam.utils.{EventReader, FileUtils, ProfilingUtils}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.events.Event
import org.matsim.core.utils.io.IOUtils
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

object RepositioningAnalyzer extends LazyLogging {
  private def getIfNotNull(rec: java.util.Map[String, String], column: String): String = {
    val v = rec.get(column)
    assert(v != null, s"Value in column '$column' is null")
    v
  }

  def toPlanInfo(rec: java.util.Map[String, String]): PlanElement = {
    // Somehow Plan file has columns in camelCase, not snake_case
    val personId = getIfNotNull(rec, "personId")
    val planElement = getIfNotNull(rec, "planElementType")
    val planElementIndex = getIfNotNull(rec, "planElementIndex").toInt
    val activityType = Option(rec.get("activityType"))
    val x = Option(rec.get("activityLocationX")).map(_.toDouble)
    val y = Option(rec.get("activityLocationY")).map(_.toDouble)
    val endTime = Option(rec.get("activityEndTime")).map(_.toDouble)
    val mode = Option(rec.get("legMode")).map(_.toString)
    PlanElement(
      personId = PersonId(personId),
      planElementType = planElement,
      planElementIndex = planElementIndex,
      activityType = activityType,
      activityLocationX = x,
      activityLocationY = y,
      activityEndTime = endTime,
      legMode = mode
    )
  }

  def writeActivities(activitiesPerHour: Map[Int, Array[PlanElement]]): Unit = {
    implicit val writer: BufferedWriter =
      IOUtils.getBufferedWriter("C:/temp/Repos/act_hour_location.csvh")
    writer.write("hour,end_x,end_y,person_id,activity_end_time")
    writer.write(System.lineSeparator())
    (0 to activitiesPerHour.keys.max).foreach { h =>
      activitiesPerHour(h).foreach { planElement =>
        writeAsString(h)
        writeAsString(planElement.activityLocationX.get)
        writeAsString(planElement.activityLocationY.get)
        writeAsString(planElement.personId.id)
        writeAsString(planElement.activityEndTime.get, shouldAddComma = false)
        writer.write(System.lineSeparator())
      }
    }
    writer.flush()
    writer.close()
  }

  def main(args: Array[String]): Unit = {
    val geoUtils = new beam.sim.common.GeoUtils {
      override def localCRS: String = "epsg:26910"
    }

    val shouldWriteActivities = false
    if (shouldWriteActivities) {
      val activitiesPerHour =
        FileUtils
          .using(
            new CsvMapReader(FileUtils.readerFromFile("C:/temp/Repos/0.plans.csv"), CsvPreference.STANDARD_PREFERENCE)
          ) { csvRdr =>
            val header = csvRdr.getHeader(true)
            Iterator.continually(csvRdr.read(header: _*)).takeWhile(_ != null).map(toPlanInfo).toArray
          }
          .filter(
            x =>
              x.planElementType == "activity" && x.activityEndTime.isDefined && !x.activityEndTime
                .contains(Double.NegativeInfinity)
          )
          .map { planElement =>
            TimeUnit.SECONDS.toHours(planElement.activityEndTime.get.toLong).toInt -> planElement
          }
          .groupBy { case (hour, _) => hour }
          .map {
            case (hour, xs) =>
              hour -> xs.map(_._2)
          }
      writeActivities(activitiesPerHour)
      println(activitiesPerHour)
    }
    // This is lazy, it just creates an iterator
    val (events: Iterator[Event], closable: Closeable) =
      EventReader.fromCsvFile("C:/temp/Repos/sfbay-smart-base__2019-06-04_02-43-10_0.events.csv", filter)

    try {
      // Actual reading happens here because we force computation by `toArray`
      val pathTraversalEvents = ProfilingUtils.timed("Read PathTraversal and filter by mode", x => logger.info(x)) {
        events
          .map(PathTraversalEvent.apply)
          .map { event =>
            val wgsStart = geoUtils.wgs2Utm(new Coord(event.startX, event.startY))
            val wgsEnd = geoUtils.wgs2Utm(new Coord(event.endX, event.endY))
            event.copy(startX = wgsStart.getX, startY = wgsStart.getY, endX = wgsEnd.getX, endY = wgsEnd.getY)
          }
          .toArray
      }
      logger.info(s"pathTraversalEvents size: ${pathTraversalEvents.length}")

      val withHour = pathTraversalEvents.map(event => TimeUnit.SECONDS.toHours(event.time.toLong).toInt -> event)
      val hourToEvents = withHour
        .groupBy { case (h, _) => h }
        .map {
          case (h, eventsThisHour) =>
            val vehToLastEvent = eventsThisHour
              .map(_._2)
              .groupBy { x =>
                x.vehicleId
              }
              .map {
                case (vehId, xs) =>
                  vehId -> xs.maxBy(x => x.time)
              }
            h -> vehToLastEvent
        }

      val shouldAccumulate: Boolean = true
      val allData = if (shouldAccumulate) {
        (0 to hourToEvents.keys.max).map { hour =>
          val dataWithPrevHours = (0 until hour).foldLeft(hourToEvents.getOrElse(hour, Map.empty)) {
            case (acc, h) =>
              val prevHourData = hourToEvents.getOrElse(h, Map.empty)
              val noInAcc = prevHourData.keySet.diff(acc.keySet)
              noInAcc.foldLeft(acc) {
                case (toUpdate, key) =>
                  toUpdate.updated(key, prevHourData(key))
              }
          }
          hour -> dataWithPrevHours
        }.toMap
      }
      else {
        hourToEvents
      }

      val accInPath = if (shouldAccumulate) "_acc" else "_noacc"
      implicit val writer: BufferedWriter =
        IOUtils.getBufferedWriter(s"C:/temp/Repos/sfbay-smart-base__2019-06-04_02-43-10_per_hour_location_${accInPath}.csvh")
      writer.write("hour,vehicle_id,end_x,end_y,time,departure_time,arrival_time,start_x,start_y")
      writer.write(System.lineSeparator())

      (0 to hourToEvents.keys.max).foreach { h =>
        allData(h).foreach {
          case (vehId, pte) =>
            writeAsString(h)
            writeAsString(vehId)
            writeAsString(pte.endX)
            writeAsString(pte.endY)
            writeAsString(pte.time)
            writeAsString(pte.departureTime)
            writeAsString(pte.arrivalTime)
            writeAsString(pte.startX)
            writeAsString(pte.startY, shouldAddComma = false)
            writer.write(System.lineSeparator())
        }
      }
      writer.flush()
      writer.close()
    } finally {
      closable.close()
    }
  }

  def writeAsString(value: Any, shouldAddComma: Boolean = true)(implicit wrt: Writer): Unit = {
    wrt.write(value.toString)
    if (shouldAddComma)
      wrt.write(',')
  }

  def filter(event: Event): Boolean = {
    val attribs = event.getAttributes
    // We need only PathTraversal for ride hail vehicles with mode `CAR`
    val isNeededEvent = event.getEventType == "PathTraversal" && Option(attribs.get("mode")).contains("car") &&
      Option(attribs.get("vehicle")).exists(vehicle => vehicle.contains("rideHailVehicle-"))
    isNeededEvent
  }

  private def writeCoord(writer: BufferedWriter, wgsCoord: Coord): Unit = {
    writer.write(wgsCoord.getY.toString)
    writer.write(',')

    writer.write(wgsCoord.getX.toString)
    writer.write(',')
  }

  private def wgsToUtm(geoUtils: GeoUtils, x: Double, y: Double): Coord = {
    val startWgsCoord = new Coord(x, y)
    val startUtmCoord = geoUtils.wgs2Utm(startWgsCoord)
    startUtmCoord
  }
}
