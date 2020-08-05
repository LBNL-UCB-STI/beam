package beam.agentsim.infrastructure.parking

import scala.language.higherKinds

import cats.Eval

import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.taz.TAZ
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id

/**
  * stores the number of stalls in use for a zone of parking stalls with a common set of attributes
  *
  * @param parkingZoneId the Id of this Zone, which directly corresponds to the Array index of this used in the ParkingZoneSearch Array[ParkingZone]
  * @param stallsAvailable a (mutable) count of stalls free, which is mutated to track the current state of stalls in a way that is logically similar to a semiphore
  * @param maxStalls the maximum number of stalls which can be in use at this ParkingZone
  * @param chargingPointType if this stall has charging, this is the type of charging
  * @param pricingModel if this stall has pricing, this is the type of pricing
  */
class ParkingZone(
  val parkingZoneId: Int,
  val tazId: Id[TAZ],
  val parkingType: ParkingType,
  var stallsAvailable: Int,
  val maxStalls: Int,
  val chargingPointType: Option[ChargingPointType],
  val pricingModel: Option[PricingModel]
) {

  /**
    * the percentage of parking available in this ParkingZone
    *
    * @return percentage [0.0, 1.0]
    */
  def availability: Double = if (maxStalls == 0) 0.0 else stallsAvailable.toDouble / maxStalls

  override def toString: String = {
    val chargeString = chargingPointType match {
      case None    => "chargingType = None"
      case Some(c) => s" chargingType = $c"
    }
    val pricingString = pricingModel match {
      case None    => "pricingModel = None"
      case Some(p) => s" pricingModel = $p"
    }
    s"ParkingZone(parkingZoneId = $parkingZoneId, numStalls = $stallsAvailable, $chargeString, $pricingString)"
  }

  def makeCopy(): ParkingZone = {
    new ParkingZone(
      this.parkingZoneId,
      this.tazId,
      this.parkingType,
      this.stallsAvailable,
      this.maxStalls,
      this.chargingPointType,
      this.pricingModel
    )
  }
}

object ParkingZone extends LazyLogging {

  val DefaultParkingZoneId: Int = -1

  // used in place of Int.MaxValue to avoid possible buffer overrun due to async failures
  // in other words, while stallsAvailable of a ParkingZone should never exceed the numStalls
  // it started with, it could be possible in the system to happen due to scheduler issues. if
  // it does, it would be more helpful for it to reflect with a reasonable number, ie., 1000001,
  // which would tell us that we had 1 extra releaseStall event.
  val UbiqiutousParkingAvailability: Int = 1000000

  val DefaultParkingZone: ParkingZone =
    ParkingZone(DefaultParkingZoneId, TAZ.DefaultTAZId, ParkingType.Public, UbiqiutousParkingAvailability, None, None)

  /**
    * creates a new StallValues object
    *
    * @param chargingType if this stall has charging, this is the type of charging
    * @param pricingModel if this stall has pricing, this is the type of pricing
    * @return a new StallValues object
    */
  def apply(
    parkingZoneId: Int,
    tazId: Id[TAZ],
    parkingType: ParkingType,
    numStalls: Int = 0,
    chargingType: Option[ChargingPointType] = None,
    pricingModel: Option[PricingModel] = None,
  ): ParkingZone = new ParkingZone(parkingZoneId, tazId, parkingType, numStalls, numStalls, chargingType, pricingModel)

  /**
    * increment the count of stalls in use
    *
    * @param parkingZone the object to increment
    * @return True|False (representing success) wrapped in an effect type
    */
  def releaseStall(parkingZone: ParkingZone): Eval[Boolean] =
    Eval.later {
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
    }

  /**
    * decrement the count of stalls in use. doesn't allow negative-values (fails silently)
    *
    * @param parkingZone the object to increment
    * @return True|False (representing success) wrapped in an effect type
    */
  def claimStall(parkingZone: ParkingZone): Eval[Boolean] =
    Eval.later {
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
    }

  /**
    * Option-wrapped Array index lookup for Array[ParkingZone]
    *
    * @param parkingZones collection of parking zones
    * @param parkingZoneId an array index
    * @return Optional ParkingZone
    */
  def getParkingZone(parkingZones: Array[ParkingZone], parkingZoneId: Int): Option[ParkingZone] = {
    if (parkingZoneId < 0 || parkingZones.length <= parkingZoneId) {
      logger.warn(s"attempting to access parking zone with illegal parkingZoneId $parkingZoneId, will be ignored")
      None
    } else {
      Some {
        parkingZones(parkingZoneId)
      }
    }
  }
}
