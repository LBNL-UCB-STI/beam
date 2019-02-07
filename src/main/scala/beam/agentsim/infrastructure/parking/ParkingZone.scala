package beam.agentsim.infrastructure.parking

import scala.language.higherKinds

import cats.Monad

import beam.agentsim.infrastructure.parking.charging.ChargingPoint

/**
  * stores the number of stalls in use for a zone of parking stalls with a common set of attributes
  *
  * @param stallsAvailable a (mutable) count of stalls free (a semiphore)
  * @param chargingPoint if this stall has charging, this is the type of charging
  * @param pricingModel if this stall has pricing, this is the type of pricing
  */
class ParkingZone(
  var stallsAvailable: Int,
  val maxStalls      : Int,
  val chargingPoint  : Option[ChargingPoint],
  val pricingModel   : Option[PricingModel]
) {
  override def toString: String = {
    val chargeString = chargingPoint match {
      case None => ""
      case Some(c) => s" chargingType = $c"
    }
    val pricingString = pricingModel match {
      case None => ""
      case Some(p) => s" pricingModel = $p"
    }
    s"StallValues(numStalls = $stallsAvailable$chargeString$pricingString)"
  }
}

object ParkingZone {
  /**
    * creates a new StallValues object
    * @param chargingType if this stall has charging, this is the type of charging
    * @param pricingModel if this stall has pricing, this is the type of pricing
    * @return a new StallValues object
    */
  def apply(
    numStalls: Int = 0,
    chargingType: Option[ChargingPoint] = None,
    pricingModel: Option[PricingModel] = None,
  ): ParkingZone = new ParkingZone(numStalls, numStalls, chargingType, pricingModel)

  /**
    * increment the count of stalls in use
    * @param stallValues the object to increment
    * @param m instance of an evaluation context
    * @tparam F an evaluation context
    * @return True|False (representing success) wrapped in an effect type
    */
  def releaseStall[F[_] : Monad](stallValues: ParkingZone)(implicit m: Monad[F]): F[Boolean] =
    m.pure {
      if (stallValues.stallsAvailable == Int.MaxValue) {
        // log debug that we tried to release a stall that would cause integer overflow
        false
      } else {
        stallValues.stallsAvailable += 1
        true
      }
    }

  /**
    * decrement the count of stalls in use. doesn't allow negative-values (fails silently)
    * @param stallValues the object to increment
    * @param m instance of an evaluation context
    * @tparam F an evaluation context
    * @return True|False (representing success) wrapped in an effect type
    */
  def claimStall[F[_] : Monad](stallValues: ParkingZone)(implicit m: Monad[F]): F[Boolean] =
    m.pure {
      if (stallValues.stallsAvailable - 1 >= 0) {
        stallValues.stallsAvailable -= 1
        true
      } else {
        // log debug that we tried to claim a stall when there were no free stalls
        false
      }
    }
}
