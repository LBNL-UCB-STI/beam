package beam.agentsim.infrastructure

import beam.agentsim.infrastructure.ParkingStall._
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import org.matsim.api.core.v01.Id

private[infrastructure] case class IndexForFilter(
  tazId: Id[TAZ],
  reservedFor: ReservedParkingType
)

private[infrastructure] case class IndexForFind(
  tazId: Id[TAZ],
  parkingType: ParkingType,
  pricingModel: PricingModel,
  reservedFor: ReservedParkingType
)

class IndexerForZonalParkingManager(resources: Map[StallAttributes, StallValues]) {
  import IndexerForZonalParkingManager._

  private[this] val mapForFilter: Map[IndexForFilter, Map[StallAttributes, StallValues]] = idxForFilter(resources)

  private[this] val mapForFind: Map[IndexForFind, Array[StallValues]] = idxForFind(resources)

  def find(
    tazId: Id[TAZ],
    parkingType: ParkingType,
    reservedFor: ReservedParkingType
  ): Seq[(IndexForFind, StallValues)] = {
    def find(key: IndexForFind): Option[(IndexForFind, StallValues)] = {
      mapForFind.get(key).flatMap { arr =>
        arr
          .find(stallValue => stallValue.numStalls > 0 && stallValue.feeInCents == 0)
          .map { found =>
            (key, found)
          }
      }
    }

    def key(pricingModel: PricingModel): IndexForFind = {
      IndexForFind(tazId = tazId, parkingType = parkingType, pricingModel = pricingModel, reservedFor = reservedFor)
    }

    // We still have to do two look-ups by key because we don't know exact pricing model
    allPricingModels.view
      .map { pricingModel =>
        find(key(pricingModel))
      }
      .filter(_.isDefined)
      .map(_.get)
  }

  def filter(
    tazWithDistance: Vector[(TAZ, Double)],
    reservedFor: ReservedParkingType
  ): Option[Map[StallAttributes, StallValues]] = {
    def find(idx: IndexForFilter): Option[Map[StallAttributes, StallValues]] = {
      mapForFilter.get(idx).map { map =>
        map.filter {
          case (_, value) =>
            value.numStalls > 0
        }
      }
    }

    def key(tazId: Id[TAZ], reservedFor: ReservedParkingType): IndexForFilter = {
      IndexForFilter(tazId = tazId, reservedFor = reservedFor)
    }

    tazWithDistance.view
      .map { case (taz, _) => find(key(taz.tazId, reservedFor)) }.find(_.isDefined).flatten
  }

}

object IndexerForZonalParkingManager {
  // TODO How to make sure that this will be in sync? One of the solution is to make runtime check using reflection
  val allPricingModels: Array[PricingModel] = Array(FlatFee, Block)

  def idxForFind(resources: Map[StallAttributes, StallValues]): Map[IndexForFind, Array[StallValues]] = {
    resources
      .groupBy {
        case (key, _) =>
          IndexForFind(
            tazId = key.tazId,
            parkingType = key.parkingType,
            pricingModel = key.pricingModel,
            reservedFor = key.reservedFor
          )
      }
      .map { case (key, map) => key -> map.values.toArray }
  }

  def idxForFilter(
    resources: Map[StallAttributes, StallValues]
  ): Map[IndexForFilter, Map[StallAttributes, StallValues]] = {
    resources.groupBy {
      case (key, _) =>
        IndexForFilter(tazId = key.tazId, reservedFor = key.reservedFor)
    }
  }
}
