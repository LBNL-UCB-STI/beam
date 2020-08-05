package beam.utils.analysis

import java.io.{BufferedReader, InputStreamReader}

import beam.router.BeamRouter.{Location, RoutingResponse}
import beam.utils.csv.CsvWriter
import beam.utils.FileUtils.{getInputStream, readerFromFile, using}
import beam.utils.NetworkHelper
import beam.utils.map.GpxPoint
import com.graphhopper.GHResponse
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.api.core.v01.population.{Activity, Person => MatsimPerson, Plan, PlanElement}
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/**
 * [[r5vsgh]] package helpers.
 */
package object r5vsgh {

  //
  // R5vsGHResultRoute
  //

  case class R5vsGHResultRoute(
    personId: Id[MatsimPerson],
    originX: Double,
    originY: Double,
    destinationX: Double,
    destinationY: Double,
    numberOfLinks: Int,
    distanceInMeters: Double,
    travelTime: Long,
    executionTimeMs: Long,
    isError: Boolean = false,
    comment: String = ""
  ) {
    def asCsvRow: IndexedSeq[String] =
      IndexedSeq(
        s"$personId",
        s"$originX",
        s"$originY",
        s"$destinationX",
        s"$destinationY",
        s"$numberOfLinks",
        s"$distanceInMeters",
        s"$travelTime",
        s"$executionTimeMs",
        if (isError) "1" else "0",
        comment
      )
  }

  object R5vsGHResultRoute {
    val csvHeader = IndexedSeq(
      "person_id",
      "origin_x",
      "origin_y",
      "destination_x",
      "destination_y",
      "number_of_links",
      "distance_in_meters",
      "travel_time",
      "execution_time_ms",
      "is_error",
      "comment"
    )
  }

  /**
   * Pass the path to ".csv" or ".csv.gz" (for GZip compression)
   * to serialize a [[R5vsGHResultRoute]] sequence.
   */
  def writeResultRoutesCsv(path: String, results: Seq[R5vsGHResultRoute]): Unit = {
    using(new CsvWriter(path, R5vsGHResultRoute.csvHeader)) { csv =>
      results.foreach { r => csv.writeRow(r.asCsvRow) }
    }
  }

  //
  // Plan
  //

  /**
   * Returns plans sample from the scenario.
   * Sample size = (persons count) * populationSamplingFactor.
   *
   * The populationSamplingFactor should be within (0.0 < x < 1.0]
   * boundary, throwing an IllegalArgumentException otherwise.
   *
   * Sorting by Id[MatsimPerson] is employed.
   */
  def takePlans(
    scenario: Scenario,
    populationSamplingFactor: Double
  ): Map[Id[MatsimPerson], Seq[PlanOD]] = {
    if (populationSamplingFactor <= 0.0 || populationSamplingFactor > 1.0) {
      throw new IllegalArgumentException(
        "Population sampling factor should be within (0.0 < x < 1.0]"
      )
    }

    val allPersonIds =
      scenario.getPopulation.getPersons.keySet().asScala.toSeq.sorted

    val populationSampleSize = {
      val s = (allPersonIds.size * populationSamplingFactor).intValue()
      if (s == 0 && populationSamplingFactor > 0) 1 else s
    }

    val personIds = allPersonIds.take(populationSampleSize)

    getPersonPlans(scenario, personIds)
  }

  /** Returns plans of certain Person IDs from the scenario. */
  def getPersonPlans(
    scenario: Scenario,
    personIds: Seq[Id[MatsimPerson]]
  ): Map[Id[MatsimPerson], List[PlanOD]] =
    scenario.getPopulation.getPersons.asScala.view
      .filter { case (personId, _) => personIds.contains(personId) }
      .aggregate(ListBuffer.empty[(Id[MatsimPerson], List[PlanOD])])(
        { case (acc, (personId, person)) =>
          acc.append((personId, makePlanODs(person.getPlans.asScala)))
          acc
        },
        { (plan1, plan2) =>
          plan1.appendAll(plan2)
          plan1
        }
      )
      .toMap

  case class PlanOD(
    origin: Location,
    destination: Location
  )

  private def makePlanODs(plans: Seq[Plan]): List[PlanOD] = {
    def makeODs(plans: List[PlanElement]): List[PlanOD] = {
      plans match {
        case Nil => Nil

        case (first: Activity) :: (tail @ (second: Activity) :: _) =>
          PlanOD(first.getCoord, second.getCoord) :: makeODs(tail)

        case (first: Activity) :: _ :: tail => makeODs(first :: tail)

        case _ :: tail => makeODs(tail)
      }
    }

    makeODs(plans.flatMap(_.getPlanElements.asScala).toList)
  }

  /** Reads PlanODs from ".csv" or ".csv.gz" files. */
  //noinspection ScalaUnusedSymbol
  private def readUtmPlanCsv(
    path: String,
    preference: CsvPreference = CsvPreference.STANDARD_PREFERENCE
  ): Map[Id[MatsimPerson], List[PlanOD]] = {

    val personPlans = collection.mutable.Map.empty[Id[MatsimPerson], ListBuffer[PlanOD]]

    var currentPersonId: Id[MatsimPerson] = null
    var prevLocation: Location = null

    using(getInputStream(path)) { csvIS =>
      using(new BufferedReader(new InputStreamReader(csvIS))) { csvBR =>
        val csvRdr = new CsvMapReader(readerFromFile(path), preference)
        val header = csvRdr.getHeader(true)

        Iterator
          .continually(csvRdr.read(header: _*))
          .takeWhile(_ != null)
          .map { entry =>
            (
              entry.get("personId"),
              entry.get("activityLocationX"),
              entry.get("activityLocationY")
            )
          }
          .filter { case (personId, x, y) =>
            personId != null && x != null && x.nonEmpty && y != null && y.nonEmpty
          }
          .foreach { tuple =>
            val (personIdStr, x, y) = tuple
            val personId = Id.createPersonId(personIdStr)

            if (personId != currentPersonId) {
              currentPersonId = personId
              prevLocation = null
            }

            val location = new Location(x.toDouble, y.toDouble)

            if (prevLocation != null) {
              if (prevLocation != null) {

                personPlans
                  .getOrElseUpdate(personId, ListBuffer.empty)
                  .append(PlanOD(prevLocation, location))
              }
            }

            prevLocation = location
          }
      }
    }

    personPlans.mapValues(_.toList).toMap
  }

  //
  //  Util
  //

  def r5ResponsesToGpxPoints(
    r5Responses: Seq[RoutingResponse],
    networkHelper: NetworkHelper,
    utm2Wgs: GeotoolsTransformation
  ): Seq[GpxPoint] = for {
    r5Resp    <- r5Responses
    itinerary <- r5Resp.itineraries
    leg       <- itinerary.legs
    linkId    <- leg.beamLeg.travelPath.linkIds
    link      = networkHelper.getLinkUnsafe(linkId)
    wgsCoord  = utm2Wgs.transform(link.getCoord)
  } yield GpxPoint(s"$linkId", wgsCoord)

  def ghResponsesToGpxPoints(
    personId: Id[MatsimPerson],
    ghResponses: Seq[GHResponse]
  ): Seq[GpxPoint] = for {
    ghResp <- ghResponses
    // Note: path.getWaypoints are just origin+destination pair
    (point, pointIdx) <- ghResp.getBest.getPoints.iterator().asScala.zipWithIndex
    } yield GpxPoint(s"$personId-$pointIdx", new Coord(point.getLon, point.getLat))

  def unwindErrorMessage(ts: java.util.List[Throwable]): String = {
    ts.asScala.map { t =>
      val msgs = ListBuffer(t.getMessage)

      var cause = t.getCause
      while (cause != null) {
        msgs += cause.getMessage
        cause = cause.getCause
      }

      msgs.mkString(sep = " <> ")
    }.mkString(sep = " <~> ")
  }
}
