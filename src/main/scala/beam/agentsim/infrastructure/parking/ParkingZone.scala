package beam.agentsim.infrastructure.parking

import scala.language.higherKinds

import cats.Monad

/**
  * stores the number of stalls in use for a zone of parking stalls with a common set of attributes
  *
  * @param numStalls a (mutable) count of stalls free (a semiphore)
  * @param feeInCents the cost to use stalls with these attributes, in cents
  */
class ParkingZone(var numStalls: Int, val feeInCents: Int) {
  override def toString: String = s"StallValues(numStalls = $numStalls, feeInCents = $feeInCents)"
}

object ParkingZone {
  /**
    * creates a new StallValues object
    * @param numStalls a (mutable) count of stalls free (a semiphore)
    * @param feeInCents the cost to use stalls with these attributes, in cents
    * @return a new StallValues object
    */
  def apply(numStalls: Int = 0, feeInCents: Int = 0): ParkingZone = new ParkingZone(numStalls, feeInCents)

  /**
    * increment the count of stalls in use
    * @param stallValues the object to increment
    * @param m instance of an evaluation context
    * @tparam F an evaluation context
    * @return ()
    */
  def releaseStall[F[_] : Monad](stallValues: ParkingZone)(implicit m: Monad[F]): F[Boolean] =
    m.pure {
      if (stallValues.numStalls == Int.MaxValue) {
        // log debug that we tried to release a stall that would cause integer overflow
        false
      } else {
        stallValues.numStalls += 1
        true
      }
    }

  /**
    * decrement the count of stalls in use. doesn't allow negative-values (fails silently)
    * @param stallValues the object to increment
    * @param m instance of an evaluation context
    * @tparam F an evaluation context
    * @return ()
    */
  def claimStall[F[_] : Monad](stallValues: ParkingZone)(implicit m: Monad[F]): F[Boolean] =
    m.pure {
      if (stallValues.numStalls - 1 >= 0) {
        stallValues.numStalls -= 1
        true
      } else {
        // log debug that we tried to claim a stall when there were no free stalls
        false
      }
    }
}
