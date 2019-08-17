package beam.agentsim.infrastructure.parking

import beam.agentsim.agents.choice.logit.MultinomialLogit
import scala.util.{Failure, Random, Success, Try}

import beam.agentsim.infrastructure.charging._
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import beam.sim.common.GeoUtils
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import scala.collection.JavaConverters._
import scala.annotation.tailrec

import beam.agentsim.infrastructure.ParkingStall

object ParkingZoneSearch {

  /**
    * a nested structure to support a search over available parking attributes,
    * where traversal either terminates in an un-defined branch (no options found),
    * or a leaf, which contains the index of a ParkingZone in the ParkingZones lookup array
    * with the matching attributes. type parameter A is a tag from a graph partitioning, such as a TAZ,
    * or possibly an h3 label.
    */
  type ZoneSearchTree[A] = Map[Id[A], Map[ParkingType, List[Int]]]

  // increases search radius by this factor at each iteration
  val SearchFactor: Double = 2.0

  // fallback value for stall pricing model evaluation
  val DefaultParkingPrice: Double = 0.0

  case class ParkingZoneSearchConfiguration(
    searchStartRadius: Double,
    searchMaxRadius: Double,
    boundingBox: Envelope,
    distanceFunction: (Coord, Coord) => Double,
    searchExpansionFactor: Double = 2.0,
  )

  case class ParkingZoneSearchParams(
    destinationUTM: Location,
    parkingDuration: Double,
    multinomialLogit: MultinomialLogit[ParkingZoneSearch.ParkingAlternative, String],
    zoneSearchTree: ZoneSearchTree[TAZ],
    parkingZones: Array[ParkingZone],
    zoneQuadTree: QuadTree[TAZ],
    random: Random,
    parkingTypes: Seq[ParkingType] = ParkingType.AllTypes
  )

  def incrementalParkingZoneSearch(
    config: ParkingZoneSearchConfiguration,
    params: ParkingZoneSearchParams,
    parkingZoneFilterFunction: ParkingZone => Boolean,
    parkingZoneLocSamplingFunction: ParkingZone => Coord,
    parkingZoneMNLParamsFunction: ParkingAlternative => (ParkingAlternative, Map[String, Double])
  ): Option[(ParkingZone, ParkingStall)] = {

    // find zones
    @tailrec
    def _search(thisInnerRadius: Double, thisOuterRadius: Double): Option[(ParkingZone, ParkingStall)] = {
      if (thisInnerRadius > config.searchMaxRadius) None
      else {

        // a lookup of the (next) search ring for TAZs
        val theseZones: List[TAZ] =
          params.zoneQuadTree
            .getRing(params.destinationUTM.getX, params.destinationUTM.getY, thisInnerRadius, thisOuterRadius)
            .asScala
            .toList

        // ParkingZones as as ParkingAlternatives
        val alternatives: Map[ParkingAlternative, Map[String, Double]] = {
          for {
            zone                <- theseZones
            parkingTypesSubtree <- params.zoneSearchTree.get(zone.tazId).toList
            parkingType         <- params.parkingTypes
            parkingZoneIds      <- parkingTypesSubtree.get(parkingType).toList
            parkingZoneId       <- parkingZoneIds
            parkingZone         <- ParkingZone.getParkingZone(params.parkingZones, parkingZoneId)
            if parkingZoneFilterFunction(parkingZone)
          } yield {
            // wrap ParkingZone in a ParkingAlternative
            val stallLocation: Coord = parkingZoneLocSamplingFunction(parkingZone)
            val stallPrice: Double =
              parkingZone.pricingModel match {
                case None => 0
                case Some(pricingModel) =>
                  PricingModel.evaluateParkingTicket(pricingModel, params.parkingDuration.toInt)
              }
            val parkingAlternative: ParkingAlternative =
              ParkingAlternative(zone, parkingType, parkingZone, stallLocation, stallPrice)
            parkingZoneMNLParamsFunction(parkingAlternative)
          }
        }.toMap

        if (alternatives.isEmpty) {
          _search(thisOuterRadius, thisOuterRadius * config.searchExpansionFactor)
        } else {

          params.multinomialLogit.sampleAlternative(alternatives, params.random).map { result =>
            val ParkingAlternative(taz, parkingType, parkingZone, coordinate, cost) = result.alternativeType

            // create a new stall instance. you win!
            val parkingStall = ParkingStall(
              taz.tazId,
              parkingZone.parkingZoneId,
              coordinate,
              cost,
              parkingZone.chargingPointType,
              parkingZone.pricingModel,
              parkingType
            )

            (parkingZone, parkingStall)
          }
        }
      }
    }
    _search(0, config.searchStartRadius)
  }

  /**
    * these are the alternatives that are generated/instantiated by a search
    * and then are selected by a sampling function
    *
    * @param taz TAZ of the alternative
    * @param parkingType parking type of the alternative
    * @param parkingZone parking zone of the alternative
    * @param coord location sampled for this alternative
    * @param cost expected cost for using this alternative
    */
  case class ParkingAlternative(
    taz: TAZ,
    parkingType: ParkingType,
    parkingZone: ParkingZone,
    coord: Coord,
    cost: Double
  )
}
