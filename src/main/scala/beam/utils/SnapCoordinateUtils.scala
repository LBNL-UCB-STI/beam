package beam.utils

import beam.sim.common.GeoUtils
import beam.utils.csv.CsvWriter
import com.conveyal.r5.streets.StreetLayer
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Coord

import scala.collection.concurrent.TrieMap

object SnapCoordinateUtils extends LazyLogging {

  trait Result

  object Result {
    final case object OutOfBoundingBoxError extends Result
    final case object R5SplitNullError extends Result
    final case class Succeed(splitCoord: Coord) extends Result
  }

  final case class SnapLocationHelper(geo: GeoUtils, streetLayer: StreetLayer, maxRadius: Double) {
    private val store: TrieMap[Coord, Option[Coord]] = TrieMap.empty

    def computeResult[A](utmCoord: Coord): Result = {
      val wgsCoord = geo.utm2Wgs(utmCoord)
      if (streetLayer.envelope.contains(wgsCoord.getX, wgsCoord.getY)) {
        val snapCoordOpt = store.getOrElseUpdate(
          utmCoord,
          Option(geo.getR5Split(streetLayer, wgsCoord, maxRadius)).map { split =>
            val updatedPlanCoord = geo.splitToCoord(split)
            geo.wgs2Utm(updatedPlanCoord)
          }
        )
        snapCoordOpt.fold[Result](Result.R5SplitNullError)(Result.Succeed)
      } else Result.OutOfBoundingBoxError
    }
  }

  object Error {
    val OutOfBoundingBox = "OutOfBoundingBox"
    val R5SplitNull = "R5SplitNull"
  }

  object Category {
    val ScenarioPerson = "Person"
    val ScenarioHousehold = "Household"
    val FreightTour = "Tour"
    val FreightPayloadPlan = "PayloadPlan"
    val FreightCarrier = "Carrier"
  }

  final case class ErrorInfo(id: String, category: String, error: String, planX: Double, planY: Double)
  final case class Processed[A](data: Seq[A] = Seq.empty, errors: Seq[ErrorInfo] = Seq.empty)

  def writeToCsv(path: String, errors: Seq[ErrorInfo]): Unit = {
    new CsvWriter(path, "id", "category", "error", "x", "y")
      .writeAllAndClose(
        errors.map(error => List(error.id, error.category, error.error, error.planX, error.planY))
      )
    logger.info("See location error info at {}.", path)
  }

}
