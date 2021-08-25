package beam.agentsim.infrastructure.parking

import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.infrastructure.charging.ChargingPointType
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id

import scala.language.higherKinds

trait ParkingZoneId

/**
  * stores the number of stalls in use for a zone of parking stalls with a common set of attributes
  *
  * @param parkingZoneId the Id of this Zone, which directly corresponds to the Array index of this used in the ParkingZoneSearch Array[ParkingZone]
  * @param stallsAvailable a (mutable) count of stalls free, which is mutated to track the current state of stalls in a way that is logically similar to a semiphore
  * @param maxStalls the maximum number of stalls which can be in use at this ParkingZone
  * @param chargingPointType if this stall has charging, this is the type of charging
  * @param pricingModel if this stall has pricing, this is the type of pricing
  */
class ParkingZone[GEO](
  val parkingZoneId: Id[ParkingZoneId],
  val geoId: Id[GEO],
  val parkingType: ParkingType,
  var stallsAvailable: Int,
  val maxStalls: Int,
  val reservedFor: Id[VehicleManager],
  val chargingPointType: Option[ChargingPointType],
  val pricingModel: Option[PricingModel],
  val timeRestrictions: Map[VehicleCategory, Range],
  val parkingZoneName: Option[String],
  val landCostInUSDPerSqft: Option[Double]
) {

  /**
    * the percentage of parking available in this ParkingZone
    *
    * @return percentage [0.0, 1.0]
    */
  def availability: Double = if (maxStalls == 0) 0.0 else stallsAvailable.toDouble / maxStalls

  override def toString: String = {
    val chargeString = chargingPointType match {
      case None    => "chargingPointType = None"
      case Some(c) => s" chargingPointType = $c"
    }
    val pricingString = pricingModel match {
      case None    => "pricingModel = None"
      case Some(p) => s" pricingModel = $p"
    }
    s"ParkingZone(parkingZoneId = $parkingZoneId, numStalls = $stallsAvailable, $chargeString, $pricingString)"
  }

  def makeCopy(maxStalls: Int = -1): ParkingZone[GEO] = {
    new ParkingZone(
      this.parkingZoneId,
      this.geoId,
      this.parkingType,
      this.stallsAvailable,
      if (maxStalls == -1) this.maxStalls else maxStalls,
      this.reservedFor,
      this.chargingPointType,
      this.pricingModel,
      this.timeRestrictions,
      this.parkingZoneName,
      this.landCostInUSDPerSqft
    )
  }

  override def equals(that: Any): Boolean =
    that match {
      case that: ParkingZone[_] => that.hashCode() == hashCode
      case _                    => false
    }
  override def hashCode: Int = parkingZoneId.hashCode()
}

object ParkingZone extends LazyLogging {

  val DefaultParkingZoneId: Id[ParkingZoneId] = Id.create("default", classOf[ParkingZoneId])

  val GlobalReservedFor: Id[VehicleManager] =
    VehicleManager.createOrGetIdUsingUnique("Global", VehicleManager.BEAMCore)

  // used in place of Int.MaxValue to avoid possible buffer overrun due to async failures
  // in other words, while stallsAvailable of a ParkingZone should never exceed the numStalls
  // it started with, it could be possible in the system to happen due to scheduler issues. if
  // it does, it would be more helpful for it to reflect with a reasonable number, ie., 1000001,
  // which would tell us that we had 1 extra releaseStall event.
  val UbiqiutousParkingAvailability: Int = 1000000

  /**
    * creates a new StallValues object
    *
    * @param chargingPointType if this stall has charging, this is the type of charging
    * @param pricingModel if this stall has pricing, this is the type of pricing
    * @return a new StallValues object
    */
  private def apply[GEO](
    parkingZoneId: Id[ParkingZoneId],
    geoId: Id[GEO],
    parkingType: ParkingType,
    reservedFor: Id[VehicleManager],
    stallsAvailable: Int = 0,
    maxStalls: Int = 0,
    chargingPointType: Option[ChargingPointType] = None,
    pricingModel: Option[PricingModel] = None,
    timeRestrictions: Map[VehicleCategory, Range] = Map.empty,
    parkingZoneName: Option[String] = None,
    landCostInUSDPerSqft: Option[Double] = None
  ): ParkingZone[GEO] =
    new ParkingZone[GEO](
      parkingZoneId,
      geoId,
      parkingType,
      stallsAvailable,
      maxStalls,
      reservedFor,
      chargingPointType,
      pricingModel,
      timeRestrictions,
      parkingZoneName,
      landCostInUSDPerSqft
    )

  def defaultInit[GEO](
    geoId: Id[GEO],
    parkingType: ParkingType,
    numStalls: Int
  ): ParkingZone[GEO] = {
    init[GEO](Some(DefaultParkingZoneId), geoId, parkingType, GlobalReservedFor, numStalls)
  }

  def init[GEO](
    parkingZoneIdMaybe: Option[Id[ParkingZoneId]],
    geoId: Id[GEO],
    parkingType: ParkingType,
    reservedFor: Id[VehicleManager],
    maxStalls: Int = 0,
    chargingPointType: Option[ChargingPointType] = None,
    pricingModel: Option[PricingModel] = None,
    timeRestrictions: Map[VehicleCategory, Range] = Map.empty,
    parkingZoneName: Option[String] = None,
    landCostInUSDPerSqft: Option[Double] = None
  ): ParkingZone[GEO] = {
    val parkingZoneId = parkingZoneIdMaybe match {
      case Some(parkingZoneId) => parkingZoneId
      case _ =>
        constructParkingZoneKey(reservedFor, geoId, parkingType, chargingPointType, pricingModel, maxStalls)
    }
    ParkingZone[GEO](
      parkingZoneId,
      geoId,
      parkingType,
      reservedFor,
      maxStalls,
      maxStalls,
      chargingPointType,
      pricingModel,
      timeRestrictions,
      parkingZoneName,
      landCostInUSDPerSqft
    )
  }

  /**
    * increment the count of stalls in use
    *
    * @param parkingZone the object to increment
    * @return True|False (representing success) wrapped in an effect type
    */
  def releaseStall[GEO](parkingZone: ParkingZone[GEO]): Boolean =
    if (parkingZone.parkingZoneId == DefaultParkingZoneId) {
      // this zone does not exist in memory but it has infinitely many stalls to release
      true
    } else if (parkingZone.stallsAvailable + 1 > parkingZone.maxStalls) {
//        log.debug(s"Attempting to release a parking stall when ParkingZone is already full.")
      false
    } else {
      parkingZone.stallsAvailable += 1
      true
    }

  /**
    * decrement the count of stalls in use. doesn't allow negative-values (fails silently)
    *
    * @param parkingZone the object to increment
    * @return True|False (representing success) wrapped in an effect type
    */
  def claimStall[GEO](parkingZone: ParkingZone[GEO]): Boolean =
    if (parkingZone.parkingZoneId == DefaultParkingZoneId) {
      // this zone does not exist in memory but it has infinitely many stalls to release
      true
    } else if (parkingZone.stallsAvailable - 1 >= 0) {
      parkingZone.stallsAvailable -= 1
      true
    } else {
      // log debug that we tried to claim a stall when there were no free stalls
      false
    }

  /**
    * Option-wrapped Array index lookup for Array[ParkingZone]
    *
    * @param parkingZones collection of parking zones
    * @param parkingZoneId an array index
    * @return Optional ParkingZone
    */
  def getParkingZone[GEO](
    parkingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]],
    parkingZoneId: Id[ParkingZoneId]
  ): Option[ParkingZone[GEO]] = {
    if (!parkingZones.contains(parkingZoneId)) {
      logger.warn(s"attempting to access parking zone with illegal parkingZoneId $parkingZoneId, will be ignored")
      None
    } else {
      Some {
        parkingZones(parkingZoneId)
      }
    }
  }

  /**
    * construct ID of a Parking Zone
    * @param vehicleManagerId Vehicle Manager
    * @param geoId TAZ ID
    * @param parkingType Parking Type
    * @param chargingPointType Charging Point Type
    * @return
    */
  def constructParkingZoneKey(
    vehicleManagerId: Id[VehicleManager],
    geoId: Id[_],
    parkingType: ParkingType,
    chargingPointTypeMaybe: Option[ChargingPointType],
    pricingModelMaybe: Option[PricingModel],
    numStalls: Int
  ): Id[ParkingZoneId] = {
    val chargingPointType = chargingPointTypeMaybe.getOrElse("NA")
    val pricingModel = pricingModelMaybe.getOrElse("NA")
    val costInCents = pricingModelMaybe.map(x => (x.costInDollars * 100).toInt).getOrElse(0)
    createId(
      s"cs_${vehicleManagerId}_${geoId}_${parkingType}_${chargingPointType}_${pricingModel}_${costInCents}_$numStalls"
    )
  }

  /**
    * create Id
    * @param zoneId the zone Id
    * @return
    */
  def createId(zoneId: String): Id[ParkingZoneId] = Id.create(zoneId, classOf[ParkingZoneId])
}
