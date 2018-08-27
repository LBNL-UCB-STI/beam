package beam.playground.sid.functional

import beam.router.Modes.BeamMode
import org.matsim.api.core.v01.Id

class Modeling {

  object common {
    type Utils = BigDecimal
  }

  sealed trait UtilityType

  case object TimeUtility extends UtilityType

  case object MonetaryUtility extends UtilityType

  sealed trait TourPurpose {
    val activityPattern: String
  }

  object TourPurpose {
    private def inferTourPurposeFromAP(activityPattern: String): Option[TourPurpose] = {
      activityPattern match {
        case "HWH" | "HWOH" => Some(Mandatory(activityPattern))
        case "WOW"          => Some(NonMandatory(activityPattern))
        case _              => None
      }
    }
  }

  case class Mandatory(override val activityPattern: String) extends TourPurpose

  case class NonMandatory(override val activityPattern: String) extends TourPurpose

  trait Alternative[A] {
    def alternativeId(t: A): Id[A]

    def mainMode(t: A): BeamMode

    def hasTransit(t: A): Boolean

    def hasDriving(t: A): Boolean
  }

  trait ChoiceSetBuilder[A, N]

  trait Nest[A, N]

  import common._

  trait UtilityComputation[A] {
    def computeUtility(t: A): Utils
  }

}
