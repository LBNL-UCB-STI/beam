package beam.utils

import beam.sim.common.GeoUtils
import beam.utils.csv.CsvWriter
import com.conveyal.r5.streets.StreetLayer
import com.typesafe.scalalogging.LazyLogging
import enumeratum._
import org.matsim.api.core.v01.Coord

import scala.collection.concurrent.TrieMap

object SnapCoordinateUtils extends LazyLogging {

  sealed abstract class Error(override val entryName: String) extends EnumEntry

  object Error extends Enum[Error] {
    val values = findValues

    case object OutOfBoundingBoxError extends Error("OutOfBoundingBox")
    case object R5SplitNullError extends Error("R5SplitNull")
  }

  sealed abstract class Category(override val entryName: String) extends EnumEntry

  object Category extends Enum[Category] {
    val values = findValues

    case object ScenarioPerson extends Category("Person")
    case object ScenarioHousehold extends Category("Household")
    case object FreightTour extends Category("Tour")
    case object FreightPayloadPlan extends Category("PayloadPlan")
    case object FreightCarrier extends Category("Carrier")
  }

  object CsvFile {
    val Plans = "snapLocationPlanErrors.csv"
    val Households = "snapLocationHouseholdErrors.csv"
    val FreightTours = "snapLocationFreightTourErrors.csv"
    val FreightPayloadPlans = "snapLocationFreightPayloadPlanErrors.csv"
    val FreightCarriers = "snapLocationFreightCarrierErrors.csv"
  }

  final case class ErrorInfo(id: String, category: Category, error: Error, planX: Double, planY: Double)

  type SnapCoordinateResult = Either[Error, Coord]

  final case class SnapLocationHelper(geo: GeoUtils, streetLayer: StreetLayer, maxRadius: Double) {
    private val store: TrieMap[Coord, Option[Coord]] = TrieMap.empty

    def find(planCoord: Coord, isWgs: Boolean = false): Option[Coord] = {
      val coord = if (isWgs) planCoord else geo.utm2Wgs(planCoord)
      store.get(coord).flatten
    }

    def computeResult(planCoord: Coord, isWgs: Boolean = false): SnapCoordinateResult = {
      val coord = if (isWgs) planCoord else geo.utm2Wgs(planCoord)
      if (streetLayer.envelope.contains(coord.getX, coord.getY)) {
        val snapCoordOpt = store.getOrElseUpdate(
          coord,
          Option(geo.getR5Split(streetLayer, coord, maxRadius)).map { split =>
            val updatedPlanCoord = geo.splitToCoord(split)
            geo.wgs2Utm(updatedPlanCoord)
          }
        )
        snapCoordOpt.fold[SnapCoordinateResult](Left(Error.R5SplitNullError))(coord => Right(coord))
      } else Left(Error.OutOfBoundingBoxError)
    }
  }

  def writeToCsv(path: String, errors: Seq[ErrorInfo]): Unit = {
    new CsvWriter(path, "id", "category", "error", "x", "y")
      .writeAllAndClose(
        errors.map(error => List(error.id, error.category.entryName, error.error.entryName, error.planX, error.planY))
      )
    logger.info("See location error info at {}.", path)
  }

}
