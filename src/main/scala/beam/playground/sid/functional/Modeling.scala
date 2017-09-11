package beam.playground.sid.functional

import beam.router.Modes.BeamMode
import beam.router.RoutingModel.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import org.matsim.api.core.v01.Id

import scalaz.Kleisli

class Modeling {

  object common {
    type Utils = BigDecimal
  }

  sealed trait UtilityType
  case object TimeUtility extends UtilityType
  case object MonetaryUtility extends UtilityType

  sealed trait TourPurpose



  trait Alternative[T]{
    def alternativeId(t:T): Id[T]

    def mainMode(t:T): BeamMode
    def hasTransit(t:T): Boolean
    def hasDriving(t:T): Boolean
  }


  import common._

  trait UtilityComputation[Alternative]{
    def computeUtility(t: Alternative): Utils
  }





}
