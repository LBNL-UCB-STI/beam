package beam.agentsim.infrastructure
import beam.agentsim.infrastructure.ParkingStall._
import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.sim.BeamServices
import org.scalatest.{Matchers, WordSpec}

import scala.collection.JavaConverters._

class IndexerForZonalParkingManagerTest extends WordSpec with Matchers {

//  val tazTreeMap: TAZTreeMap =
//    BeamServices.getTazTreeMap("test/test-resources/beam/agentsim/infrastructure/taz-centers.csv")
//  val tazes = tazTreeMap.tazQuadTree.values().asScala.toVector
//  val map = getMap(tazTreeMap)
//
//  val numOfTazIds = tazes.groupBy(x => x.tazId).keys.size
//  val numOfReservedParkingType = map.keys.groupBy(x => x.reservedFor).keys.size
//  val numOfPricingModels = map.keys.groupBy(x => x.pricingModel).keys.size
//  val numOfParkingTypes = map.keys.groupBy(x => x.parkingType).keys.size
//
//  def getMap(tazTreeMap: TAZTreeMap): Map[StallAttributes, StallValues] = {
//    (for {
//      taz          <- tazes
//      parkingType  <- List(Residential, Workplace, Public)
//      pricingModel <- List(FlatFee, Block)
//      chargingType <- List(NoCharger, Level1, Level2, DCFast, UltraFast)
//      reservedFor  <- List(ParkingStall.Any)
//    } yield {
//      val attrib = StallAttributes(taz.tazId, parkingType, pricingModel, chargingType, reservedFor)
//      (attrib, StallValues(Int.MaxValue, 0))
//    }).toMap ++ (
//      for {
//        taz          <- tazes
//        parkingType  <- List(Workplace)
//        pricingModel <- List(FlatFee)
//        chargingType <- List(Level2, DCFast, UltraFast)
//        reservedFor  <- List(ParkingStall.RideHailManager)
//      } yield {
//        val attrib = StallAttributes(taz.tazId, parkingType, pricingModel, chargingType, reservedFor)
//        (attrib, StallValues(0, 0))
//      }
//    )
//  }

  "An IndexerForZonalParkingManager object" ignore {
//    "be able to build index for filter" in {
//
//      val idxForFilter = IndexerForZonalParkingManager.idxForFilter(map)
//
//      // Index for filter has two dimensions:
//      //    tazId: Id[TAZ]                  , |tazId| = number of unique taz ids
//      //    reservedFor: ReservedParkingType, |ReservedParkingType| = 2
//      idxForFilter.keys.groupBy(_.tazId).size should be(numOfTazIds)
//      idxForFilter.keys.groupBy(_.reservedFor).size should be(numOfReservedParkingType)
//
//      map.foreach {
//        case (attr, _) =>
//          val key = IndexForFilter(tazId = attr.tazId, reservedFor = attr.reservedFor)
//          idxForFilter.contains(key) should be(true)
//      }
//    }
//
//    "be able to build index for find" in {
//      val idxForFind: Map[IndexForFind, Array[StallValues]] = IndexerForZonalParkingManager.idxForFind(map)
//
//      // Index for filter has four dimensions:
//      //    tazId: Id[TAZ]                      , |tazId| = number of unique taz ids
//      //    parkingType: ParkingType            , |ParkingType| = 4
//      //    pricingModel: PricingModel          , |PricingModel| = 2
//      //    reservedFor: ReservedParkingType    , |ReservedParkingType| = 2
//
//      idxForFind.keys.groupBy(_.tazId).size should be(numOfTazIds)
//      idxForFind.keys.groupBy(_.parkingType).size should be(numOfParkingTypes)
//      idxForFind.keys.groupBy(_.pricingModel).size should be(numOfPricingModels)
//      idxForFind.keys.groupBy(_.reservedFor).size should be(numOfReservedParkingType)
//
//      map.foreach {
//        case (attr, _) =>
//          val key = IndexForFind(
//            tazId = attr.tazId,
//            parkingType = attr.parkingType,
//            pricingModel = attr.pricingModel,
//            reservedFor = attr.reservedFor
//          )
//          idxForFind.contains(key) should be(true)
//      }
//    }
  }
}
