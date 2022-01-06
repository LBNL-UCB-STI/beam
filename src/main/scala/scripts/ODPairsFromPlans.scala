package scripts

import beam.agentsim.infrastructure.taz
import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.utils.csv.GenericCsvReader
import org.matsim.api.core.v01.Coord

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.collection.parallel.immutable.ParSet

object ODPairsFromPlans {

  // return an iterable of activity locations per person from input plans
  def readActivityCoords(
    plansPath: String,
    personIdColumn: String,
    planTypeColumn: String,
    activityLocationXColumn: String,
    activityLocationYColumn: String
  ): Iterable[Iterable[Coord]] = {

    case class PersonActivityLocation(person: String, planType: String, activityLocation: Coord)

    def toPersonActivityLocation(row: java.util.Map[String, String]): PersonActivityLocation = {
      val person = row.get(personIdColumn)
      val planType = row.get(planTypeColumn)
      val activityLocation = {
        if (planType == "activity") {
          val x = row.get(activityLocationXColumn).toDouble
          val y = row.get(activityLocationYColumn).toDouble
          new Coord(x, y)
        } else {
          new Coord(0, 0)
        }
      }
      PersonActivityLocation(person, planType, activityLocation)
    }

    val (activities, closeReader) = GenericCsvReader.readAs[PersonActivityLocation](
      plansPath,
      toPersonActivityLocation,
      { case PersonActivityLocation(_, planType, _) => planType == "activity" }
    )

    try {
      val personsToLocations: mutable.Map[String, ListBuffer[Coord]] =
        activities.foldLeft(mutable.HashMap.empty[String, mutable.ListBuffer[Coord]]) {
          case (personToLocations, PersonActivityLocation(personId, _, location)) =>
            personToLocations.get(personId) match {
              case Some(locations) => locations.append(location)
              case None            => personToLocations(personId) = mutable.ListBuffer(location)
            }
            personToLocations
        }
      personsToLocations.values
    } finally {
      closeReader.close()
    }
  }

  def listOfTAZODFromActivitiesCoords(
    plansPath: String,
    tazCentersPath: String,
    plansFormat: String
  ): ParSet[String] = {
    val activityCoords = plansFormat match {
      case "generated" =>
        readActivityCoords(plansPath, "personId", "planElementType", "activityLocationX", "activityLocationY")
      case "urbansim_v2" =>
        readActivityCoords(plansPath, "person_id", "ActivityElement", "x", "y")
    }

    val tazTreeMap: TAZTreeMap = taz.TAZTreeMap.getTazTreeMap(tazCentersPath)
    def getTaz(coord: Coord): String = { tazTreeMap.getTAZ(coord).tazId.toString }

    val ODPairs = activityCoords.par
      .flatMap { activityCoords =>
        activityCoords.sliding(2).map {
          case Seq(orig, dest) => Some(getTaz(orig), getTaz(dest))
          case _               => None
        }
      }
      .flatten
      .map { case (o, d) => s"$o,$d" }
      .toSet

    ODPairs
  }

  def main(args: Array[String]): Unit = {
    if (args.length != 3) {
      println("2 arguments expected: <path to plans> <path to TAZ centers file> <plans format: generated|urbansim_v2 >")
    } else {
      val ODPairs = listOfTAZODFromActivitiesCoords(args(0), args(1), args(2))
      println("origin,destination")
      ODPairs.foreach(ODPair => println(ODPair))
    }
  }
}
