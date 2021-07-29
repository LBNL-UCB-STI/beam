package beam.utils.map

import java.io.{BufferedWriter, Closeable, Writer}
import java.util.concurrent.TimeUnit

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.PathTraversalEvent
import beam.utils.scenario.PlanElement
import beam.utils.{EventReader, FileUtils, ProfilingUtils}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.utils.io.IOUtils
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

case class VehicleLocation(vehicleId: Id[BeamVehicle], x: Double, y: Double, time: Int, numOfPassangers: Int)

object RepositioningAnalyzer extends LazyLogging {

  private def getIfNotNull(rec: java.util.Map[String, String], column: String): String = {
    val v = rec.get(column)
    assert(v != null, s"Value in column '$column' is null")
    v
  }

  def toInitVehicalLocation(rec: java.util.Map[String, String]): VehicleLocation = {
    val id = Id.createVehicleId(getIfNotNull(rec, "id"))
    // Yes, it contains leading space
    val x = getIfNotNull(rec, " initialLocationX").toDouble
    val y = getIfNotNull(rec, " initialLocationY").toDouble
    VehicleLocation(vehicleId = id, x = x, y = y, time = 0, numOfPassangers = -1)
  }

  def toPlanInfo(rec: java.util.Map[String, String]): PlanElement = {
//    // Somehow Plan file has columns in camelCase, not snake_case
//    val personId = getIfNotNull(rec, "personId")
//    val planElement = getIfNotNull(rec, "planElementType")
//    val planElementIndex = getIfNotNull(rec, "planElementIndex").toInt
//    val activityType = Option(rec.get("activityType"))
//    val x = Option(rec.get("activityLocationX")).map(_.toDouble)
//    val y = Option(rec.get("activityLocationY")).map(_.toDouble)
//    val endTime = Option(rec.get("activityEndTime")).map(_.toDouble)
//    val mode = Option(rec.get("legMode")).map(_.toString)
//    PlanElement(
//      personId = PersonId(personId),
//      planElementType = planElement,
//      planElementIndex = planElementIndex,
//      activityType = activityType,
//      activityLocationX = x,
//      activityLocationY = y,
//      activityEndTime = endTime,
//      legMode = mode
//    )
    ???
  }

  def writeActivities(path: String, activitiesPerHour: Map[Int, Array[PlanElement]]): Unit = {
    implicit val writer: BufferedWriter =
      IOUtils.getBufferedWriter(path)
    writer.write("hour,x,y,person_id,activity_end_time")
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

    val basePath = "C:/temp/Repos/RANDOM_REPOSITIONING_ALGO_7"
    val eventsFilePath = s"$basePath/1.events.csv.gz"
    val initFleetLocationPath = s"$basePath/1.rideHailFleet.csv"
    val activityPath = "C:/temp/Repos/0.plans.csv"

    val shouldWriteActivitiesLocation = false
    if (shouldWriteActivitiesLocation) {
      val activitiesPerHour =
        FileUtils
          .using(
            new CsvMapReader(FileUtils.readerFromFile(activityPath), CsvPreference.STANDARD_PREFERENCE)
          ) { csvRdr =>
            val header = csvRdr.getHeader(true)
            Iterator.continually(csvRdr.read(header: _*)).takeWhile(_ != null).map(toPlanInfo).toArray
          }
          .filter(x =>
            x.planElementType == "activity" && x.activityEndTime.isDefined && !x.activityEndTime
              .contains(Double.NegativeInfinity)
          )
          .map { planElement =>
            TimeUnit.SECONDS.toHours(planElement.activityEndTime.get.toLong).toInt -> planElement
          }
          .groupBy { case (hour, _) => hour }
          .map { case (hour, xs) =>
            hour -> xs.map(_._2)
          }
      writeActivities(s"$basePath/act_hour_location.csvh", activitiesPerHour)
    }

    val initLoc = FileUtils
      .using(
        new CsvMapReader(FileUtils.readerFromFile(initFleetLocationPath), CsvPreference.STANDARD_PREFERENCE)
      ) { csvRdr =>
        val header = csvRdr.getHeader(true)
        Iterator.continually(csvRdr.read(header: _*)).takeWhile(_ != null).map(toInitVehicalLocation).toArray
      }

    // This is lazy, it just creates an iterator
    val (events: Iterator[Event], closable: Closeable) =
      EventReader.fromCsvFile(eventsFilePath, filter)

    try {
      // Actual reading happens here because we force computation by `toArray`
      val vehicleLocations = ProfilingUtils.timed("Read PathTraversal and filter by mode", x => logger.info(x)) {
        events
          .map(PathTraversalEvent.apply)
          .map { event =>
            val wgsEnd = geoUtils.wgs2Utm(new Coord(event.endX, event.endY))
            VehicleLocation(
              vehicleId = event.vehicleId,
              x = wgsEnd.getX,
              y = wgsEnd.getY,
              time = event.time.toInt,
              numOfPassangers = event.numberOfPassengers
            )
          }
          .toArray
      }
      logger.info(s"vehicleLocations size: ${vehicleLocations.length}")

      val withInitLocation = initLoc ++ vehicleLocations
      logger.info(s"withInitLocation size: ${withInitLocation.length}")

      val withHour = withInitLocation.map(vl => TimeUnit.SECONDS.toHours(vl.time.toLong).toInt -> vl)

      val shouldWriteVehicleLocation: Boolean = true
      if (shouldWriteVehicleLocation) {
        writeVehicleLocation(basePath, withHour)
      }
      sys.exit(1)

      val hourToLoc = withHour
        .groupBy { case (h, _) => h }
        .map { case (h, eventsThisHour) =>
          val vehToLastEvent = eventsThisHour
            .map(_._2)
            .groupBy { x =>
              x.vehicleId
            }
            .map { case (vehId, xs) =>
              vehId -> xs.maxBy(x => x.time)
            }
          h -> vehToLastEvent
        }

      val shouldAccumulate: Boolean = true
      val allData = if (shouldAccumulate) {
        (0 to hourToLoc.keys.max).map { hour =>
          val dataWithPrevHours = (0 until hour).foldLeft(hourToLoc.getOrElse(hour, Map.empty)) { case (acc, h) =>
            val prevHourData = hourToLoc.getOrElse(h, Map.empty)
            val allKeys = prevHourData.keySet ++ acc.keySet
            allKeys.foldLeft(acc) { case (toUpdate, key) =>
              val updated = (prevHourData.get(key), acc.get(key)) match {
                case (Some(prev), Some(current)) =>
                  val time = if (prev.time != 0) prev.time else current.time
                  current.copy(time = time)
                case (Some(prev), None) =>
                  prev
                case (None, Some(curr)) =>
                  curr
                case (None, None) =>
                  throw new Exception("WTF?")
              }
              toUpdate.updated(key, updated)
            }
          }
          hour -> dataWithPrevHours
        }.toMap
      } else {
        hourToLoc
      }

      val accInPath = if (shouldAccumulate) "_acc" else "_noacc"
      implicit val writer: BufferedWriter =
        IOUtils.getBufferedWriter(
          s"$basePath/per_hour_location_$accInPath.csvh"
        )
      writer.write("hour,vehicle_id,x,y,time,num_of_passengers")
      writer.write("\n")

      (0 to hourToLoc.keys.max).foreach { h =>
        allData(h).foreach { case (vehId, pte) =>
          writeAsString(h)
          writeAsString(vehId)
          writeAsString(pte.x)
          writeAsString(pte.y)
          writeAsString(pte.time)
          writeAsString(pte.numOfPassangers, shouldAddComma = false)
          writer.write("\n")
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

  def writeVehicleLocation(basePath: String, withHour: Array[(Int, VehicleLocation)]): Unit = {
    implicit val writer: BufferedWriter =
      IOUtils.getBufferedWriter(
        s"$basePath/vehicle_location.csvh"
      )
    writer.write("hour,vehicle_id,x,y,time,num_of_passengers")
    writer.write("\n")
    withHour.foreach { case (h, vehicleLocation) =>
      writeAsString(h)
      writeAsString(vehicleLocation.vehicleId)
      writeAsString(vehicleLocation.x)
      writeAsString(vehicleLocation.y)
      writeAsString(vehicleLocation.time)
      writeAsString(vehicleLocation.numOfPassangers, shouldAddComma = false)
      writer.write("\n")
    }
    writer.flush()
    writer.close()
  }
}
