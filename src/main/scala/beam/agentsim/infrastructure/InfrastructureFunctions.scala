package beam.agentsim.infrastructure

import beam.agentsim.agents.choice.logit.UtilityFunctionOperation
import beam.agentsim.infrastructure.ParkingInquiry.ParkingActivityType
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZone.UbiqiutousParkingAvailability
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.{
  ParkingAlternative,
  ParkingZoneCollection,
  ParkingZoneSearchConfiguration,
  ParkingZoneSearchParams,
  ParkingZoneSearchResult
}
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.util.Random

abstract class InfrastructureFunctions(
  geoQuadTree: QuadTree[TAZ],
  idToGeoMapping: scala.collection.Map[Id[TAZ], TAZ],
  parkingZones: Map[Id[ParkingZoneId], ParkingZone],
  distanceFunction: (Coord, Coord) => Double,
  minSearchRadius: Double,
  maxSearchRadius: Double,
  searchMaxDistanceRelativeToEllipseFoci: Double,
  enrouteDuration: Double,
  fractionOfSameTypeZones: Double,
  minNumberOfSameTypeZones: Int,
  boundingBox: Envelope,
  seed: Int,
  estimatedMinParkingDurationInSeconds: Double
) extends StrictLogging {

  val zoneCollections: Map[Id[TAZ], ParkingZoneCollection] =
    ParkingZoneSearch.createZoneCollections(parkingZones.values.toSeq)

  protected val mnlMultiplierParameters: Map[ParkingMNL.Parameters, UtilityFunctionOperation]

  /**
    * Generic method for updating MNL Parameters
    * @param parkingAlternative ParkingAlternative
    * @param inquiry ParkingInquiry
    * @return
    */
  protected def setupMNLParameters(
    parkingAlternative: ParkingAlternative,
    inquiry: ParkingInquiry
  ): Map[ParkingMNL.Parameters, Double]

  /**
    * Generic method for adding new search filter to parking zones
    * @param zone ParkingZone
    * @param inquiry ParkingInquiry
    * @return
    */
  protected def setupSearchFilterPredicates(zone: ParkingZone, inquiry: ParkingInquiry): Boolean

  /**
    * Generic method that specifies the behavior when MNL returns a ParkingZoneSearchResult
    * @param parkingZoneSearchResult ParkingZoneSearchResult
    */
  protected def processParkingZoneSearchResult(
    inquiry: ParkingInquiry,
    parkingZoneSearchResult: Option[ParkingZoneSearchResult]
  ): Option[ParkingZoneSearchResult]

  /**
    * sample location of a parking stall
    * @param inquiry ParkingInquiry
    * @param parkingZone ParkingZone
    * @param taz TAZ
    * @return
    */
  protected def sampleParkingStallLocation(
    inquiry: ParkingInquiry,
    parkingZone: ParkingZone,
    taz: TAZ,
    inClosestZone: Boolean = false
  ): Coord

  // ************

  import InfrastructureFunctions._

  val DefaultParkingZone: ParkingZone =
    ParkingZone.defaultInit(
      TAZ.DefaultTAZId,
      ParkingType.Public,
      UbiqiutousParkingAvailability
    )

  val parkingZoneSearchConfiguration: ParkingZoneSearchConfiguration =
    ParkingZoneSearchConfiguration(
      minSearchRadius,
      maxSearchRadius,
      searchMaxDistanceRelativeToEllipseFoci,
      boundingBox,
      distanceFunction,
      enrouteDuration,
      estimatedMinParkingDurationInSeconds,
      fractionOfSameTypeZones,
      minNumberOfSameTypeZones
    )

  def searchForParkingStall(inquiry: ParkingInquiry): Option[ParkingZoneSearch.ParkingZoneSearchResult] = {
    // ---------------------------------------------------------------------------------------------
    // a ParkingZoneSearch takes the following as parameters
    //
    //   ParkingZoneSearchConfiguration: static settings for all searches
    //   ParkingZoneSearchParams: things specific to this inquiry/state of simulation
    //   parkingZoneFilterFunction: a predicate which is applied as a filter for each search result
    //     which filters out the search case, typically due to ParkingZone/ParkingInquiry fields
    //   parkingZoneLocSamplingFunction: this function creates a ParkingStall from a ParkingZone
    //     by sampling a location for a stall
    //   parkingZoneMNLParamsFunction: this is used to decorate each ParkingAlternative with
    //     utility function parameters. all alternatives are sampled in a multinomial logit function
    //     based on this.
    // ---------------------------------------------------------------------------------------------

    val parkingZoneSearchParams: ParkingZoneSearchParams =
      ParkingZoneSearchParams(
        inquiry.destinationUtm.loc,
        inquiry.parkingDuration,
        inquiry.searchMode,
        mnlMultiplierParameters,
        zoneCollections,
        parkingZones,
        geoQuadTree,
        new Random(seed),
        inquiry.departureLocation,
        inquiry.reservedFor
      )

    val closestZone =
      Option(
        parkingZoneSearchParams.zoneQuadTree
          .getClosest(inquiry.destinationUtm.loc.getX, inquiry.destinationUtm.loc.getY)
      )

    val closestZoneId = closestZone match {
      case Some(foundZone) => foundZone.tazId
      case _               => TAZ.EmergencyTAZId
    }

    // filters out ParkingZones which do not apply to this agent
    // TODO: check for conflicts between variables here - is it always false?
    val parkingZoneFilterFunction: ParkingZone => Boolean =
      (zone: ParkingZone) => setupSearchFilterPredicates(zone, inquiry)

    // generates a coordinate for an embodied ParkingStall from a ParkingZone
    val parkingZoneLocSamplingFunction: ParkingZone => Coord =
      (zone: ParkingZone) => {
        idToGeoMapping.get(zone.tazId) match {
          case None =>
            logger.error(
              s"somehow have a ParkingZone with tazId ${zone.tazId} which is not found in the idToGeoMapping"
            )
            new Coord()
          case Some(taz) =>
            val inClosestZone = closestZoneId == zone.tazId
            sampleParkingStallLocation(inquiry, zone, taz, inClosestZone)
        }
      }

    // adds multinomial logit parameters to a ParkingAlternative
    val parkingZoneMNLParamsFunction: ParkingAlternative => Map[ParkingMNL.Parameters, Double] =
      (parkingAlternative: ParkingAlternative) => {
        val params = setupMNLParameters(parkingAlternative, inquiry)
        if (inquiry.parkingActivityType == ParkingActivityType.Home) {
          logger.debug(
            f"tour=${inquiry.remainingTripData
              .map {
                _.remainingTourDistance
              }
              .getOrElse(0.0)}%.2f ${ParkingMNL.prettyPrintAlternatives(params)}"
          )
        }
        params
      }

    ///////////////////////////////////////////
    // run ParkingZoneSearch for a ParkingStall
    ///////////////////////////////////////////
    val result = processParkingZoneSearchResult(
      inquiry,
      ParkingZoneSearch.incrementalParkingZoneSearch(
        parkingZoneSearchConfiguration,
        parkingZoneSearchParams,
        parkingZoneFilterFunction,
        parkingZoneLocSamplingFunction,
        parkingZoneMNLParamsFunction
      )
    )

    result match {
      case Some(
            _ @ParkingZoneSearch.ParkingZoneSearchResult(
              _,
              _,
              parkingZonesSeen,
              parkingZonesSampled,
              iterations
            )
          ) =>
        logger.debug(
          s"sampled over ${parkingZonesSampled.length} (found ${parkingZonesSeen.length}) parking zones over $iterations iterations."
        )
        logger.debug(
          "sampled stats:\n    ChargerTypes: {};\n    Parking Types: {};\n    Costs: {};",
          chargingTypeToNo(parkingZonesSampled),
          parkingTypeToNo(parkingZonesSampled),
          listOfCosts(parkingZonesSampled)
        )
      case _ =>
    }

    result
  }

  def claimStall(parkingZone: ParkingZone): Boolean = {
    val result = ParkingZone.claimStall(parkingZone)
    zoneCollections.get(parkingZone.tazId).foreach(_.claimZone(parkingZone))
    result
  }

  def releaseStall(parkingZone: ParkingZone): Boolean = {
    val result = ParkingZone.releaseStall(parkingZone)
    zoneCollections.get(parkingZone.tazId).foreach(_.releaseZone(parkingZone))
    result
  }

}

object InfrastructureFunctions {

  def chargingTypeToNo(
    parkingZonesSampled: List[(Id[ParkingZoneId], Option[ChargingPointType], ParkingType, Double)]
  ): String = {
    parkingZonesSampled
      .map(triple =>
        triple._2 match {
          case Some(x) => x
          case None    => "NoCharger"
        }
      )
      .groupBy(identity)
      .mapValues(_.size)
      .map(x => x._1.toString + ": " + x._2)
      .mkString(", ")
  }

  def parkingTypeToNo(
    parkingZonesSampled: List[(Id[ParkingZoneId], Option[ChargingPointType], ParkingType, Double)]
  ): String = {
    parkingZonesSampled
      .map(triple => triple._3)
      .groupBy(identity)
      .mapValues(_.size)
      .map(x => x._1.toString + ": " + x._2)
      .mkString(", ")
  }

  def listOfCosts(
    parkingZonesSampled: List[(Id[ParkingZoneId], Option[ChargingPointType], ParkingType, Double)]
  ): String = {
    parkingZonesSampled
      .map(triple => triple._4)
      .groupBy(identity)
      .mapValues(_.size)
      .map(x => x._1.toString + ": " + x._2)
      .mkString(", ")
  }
}
