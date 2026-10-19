package beam.utils.csv.readers

import java.util.{Map => JavaMap}
import beam.utils.csv.GenericCsvReader.getIfNotNull
import beam.utils.csv.GenericCsvReader.getOrDefault
import beam.utils.csv.writers.ScenarioCsvWriter.ArrayItemSeparator
import beam.utils.logging.ExponentialLazyLogging
import beam.utils.scenario._
import beam.utils.scenario.matsim.BeamScenarioReader
import beam.utils.{FileUtils, ProfilingUtils}
import beam.utils.scenario._
import org.apache.commons.lang3.math.NumberUtils
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.reflect.ClassTag
import scala.util.Try

object BeamCsvScenarioReader extends BeamScenarioReader with ExponentialLazyLogging {

  override def inputType: InputType = InputType.CSV

  override def readPersonsFile(path: String): Array[PersonInfo] = {
    readAs[PersonInfo](path, "readPersonsFile", toPersonInfo)
  }

  override def readPlansFile(path: String): Array[PlanElement] = {
    readAs[PlanElement](path, "readPlansFile", toPlanInfo)
  }

  override def readHouseholdsFile(householdsPath: String, vehicles: Iterable[VehicleInfo]): Array[HouseholdInfo] = {
    val householdToNumberOfCars = vehicles.groupBy(_.householdId).map { case (householdId, listOfCars) =>
      (householdId, listOfCars.size)
    }
    readAs[HouseholdInfo](householdsPath, "readHouseholdsFile", toHouseholdInfo(householdToNumberOfCars))
  }

  private[readers] def readAs[T](path: String, what: String, mapper: JavaMap[String, String] => T)(implicit
    ct: ClassTag[T]
  ): Array[T] = {
    ProfilingUtils.timed(what, x => logger.info(x)) {
      FileUtils.using(new CsvMapReader(FileUtils.readerFromFile(path), CsvPreference.STANDARD_PREFERENCE)) { csvRdr =>
        val header = csvRdr.getHeader(true)
        Iterator.continually(csvRdr.read(header: _*)).takeWhile(_ != null).map(mapper).toArray
      }
    }
  }

  private[readers] def toHouseholdInfo(
    householdIdToVehiclesSize: Map[String, Int]
  )(rec: JavaMap[String, String]): HouseholdInfo = {
    val householdId = getIfNotNull(rec, "householdId")
    val cars = householdIdToVehiclesSize.get(householdId) match {
      case Some(total) => total
      case None        =>
        // The dots here are due to an idiosyncrasy with how ExponentialLazyLogger works....
        // it has to track how many times a log message has been requested and it uses the first
        // 20 characters as a unique key... if message is fewer than 20 characters the intended
        // behavior fails.
        logger.warn(s"HouseholdId has no cars associated........Id [$householdId]")
        0
    }
    HouseholdInfo(
      householdId = HouseholdId(householdId),
      cars = cars,
      income = getIfNotNull(rec, "incomeValue").toDouble,
      locationX = getIfNotNull(rec, "locationX").toDouble,
      locationY = getIfNotNull(rec, "locationY").toDouble
    )
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

  private[readers] def toPlanInfo(rec: java.util.Map[String, String]): PlanElement = {
    val personId = getIfNotNull(rec, "personId")
    val planIndex = parseDouble(rec.get("planIndex")).map(_.toInt).getOrElse(0)
    val planElementType = getIfNotNull(rec, "planElementType")
    val planElementIndex = parseDouble(rec.get("planElementIndex")).map(_.toInt).getOrElse(0)
    val activityType = parseString(rec.get("activityType"))
    val linkIds =
      Option(rec.get("legRouteLinks")).map(_.split(ArrayItemSeparator).map(_.trim)).getOrElse(Array.empty[String])
    val tripId = Option(rec.get("tripId"))
      .orElse(Option(rec.get("trip_id")))
      .map(_.filter(x => x.isDigit || x.equals('.')))
      .filterNot(s => s.isEmpty || s.equalsIgnoreCase("null"))
      .getOrElse("")
    val tourId = Option(rec.get("tourId"))
      .orElse(Option(rec.get("tour_id")))
      .map(_.filter(x => x.isDigit || x.equals('.')))
      .filterNot(s => s.isEmpty || s.equalsIgnoreCase("null"))
      .getOrElse("")

    PlanElement(
      tripId = tripId,
      tourId = tourId,
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

  private def toPersonInfo(rec: JavaMap[String, String]): PersonInfo = {
    val personId = getIfNotNull(rec, "personId")
    val householdId = getIfNotNull(rec, "householdId")
    val age = getIfNotNull(rec, "age").toInt
    val isFemale = getOrDefault(rec, "isFemale", "false").toBoolean
    val rank = getOrDefault(rec, "householdRank", "0").toInt
    val industry = Option(rec.get("industry"))
    val excludedModes = getOrDefault(rec, "excludedModes", "").split(",")
    val rideHailServiceSubscription =
      Option(rec.get("rideHailServiceSubscription")).fold(Seq.empty[String])(_.split(","))
    val valueOfTime = NumberUtils.toDouble(getOrDefault(rec, "valueOfTime", "0"), 0d)
    PersonInfo(
      personId = PersonId(personId),
      householdId = HouseholdId(householdId),
      rank = rank,
      age = age,
      excludedModes = excludedModes,
      rideHailServiceSubscription = rideHailServiceSubscription,
      isFemale = isFemale,
      valueOfTime = valueOfTime,
      industry = industry
    )
  }

  private def toVehicle(rec: JavaMap[String, String]): VehicleInfo = {
    VehicleInfo(
      vehicleId = getIfNotNull(rec, "vehicleId"),
      vehicleTypeId = getIfNotNull(rec, "vehicleTypeId"),
      initialSoc = Option(rec.get("stateOfCharge")).map(_.trim).filterNot(_.isEmpty).map(_.toDouble),
      householdId = getIfNotNull(rec, "householdId")
    )
  }

  override def readVehiclesFile(vehiclesFilePath: String): Iterable[VehicleInfo] = {
    readAs[VehicleInfo](vehiclesFilePath, "vehiclesFile", toVehicle)
  }
}
