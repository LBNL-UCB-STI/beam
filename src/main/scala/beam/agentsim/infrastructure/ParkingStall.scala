package beam.agentsim.infrastructure

import beam.agentsim.Resource
import beam.agentsim.infrastructure.ParkingStall.{StallAttributes, StallValues}
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.EmbodiedBeamLeg
import org.matsim.api.core.v01.Id

class ParkingStall(
  val id: Id[ParkingStall],
  val attributes: StallAttributes,
  val location: Location,
  val cost: Double,
  val stallValues: Option[StallValues]
) extends Resource[ParkingStall] {
  override def getId: Id[ParkingStall] = id
}

object ParkingStall {
  case class StallAttributes(
    val tazId: Id[TAZ],
    val parkingType: ParkingType,
    val pricingModel: PricingModel,
    val chargingType: ChargingType,
    val reservedFor: ReservedParkingType
  )
  case class StallValues(numStalls: Int, feeInCents: Int)

  sealed trait ParkingType
  case object Residential extends ParkingType
  case object Workplace extends ParkingType
  case object Public extends ParkingType
  case object NoOtherExists extends ParkingType

  object ParkingType {

    def fromString(s: String): ParkingType = {
      s match {
        case "Residential"   => Residential
        case "Workplace"     => Workplace
        case "Public"        => Public
        case "NoOtherExists" => NoOtherExists
        case _               => throw new RuntimeException("Invalid case")
      }
    }
  }

//  lazy val parkingMap = Map[Int, ParkingType](
//    1 -> Residential, 2 -> Workplace, 3 -> Public, 4 -> NoOtherExists
//  )

  sealed trait ChargingType

  case object NoCharger extends ChargingType
  case object Level1 extends ChargingType
  case object Level2 extends ChargingType
  case object DCFast extends ChargingType
  case object UltraFast extends ChargingType

  object ChargingType {

    def fromString(s: String): ChargingType = {
      s match {
        case "NoCharger" => NoCharger
        case "Level1"    => Level1
        case "Level2"    => Level2
        case "DCFast"    => DCFast
        case "UltraFast" => UltraFast
        case _           => throw new RuntimeException("Invalid case")
      }
    }
  }

//  lazy val chargingMap = Map[Int, ChargingType](
//    1 -> NoCharger, 2 -> Level1, 3 -> Level2, 4 -> DCFast, 5 -> UltraFast
//  )

  sealed trait ChargingPreference
  case object NoNeed extends ChargingPreference
  case object MustCharge extends ChargingPreference
  case object Opportunistic extends ChargingPreference

  /*
   *  Flat fee means one price is paid independent of time
   *  Block means price is hourly and can change with the amount of time at the spot (e.g. first hour $1, after than $2/hour)
   *
   *  Use block price even if price is a simple hourly price.
   */
  sealed trait PricingModel
  case object Free extends PricingModel
  case object FlatFee extends PricingModel
  case object Block extends PricingModel

  object PricingModel {

    def fromString(s: String): PricingModel = s match {
      case "Free"    => Free
      case "FlatFee" => FlatFee
      case "Block"   => Block
    }
  }

  sealed trait ReservedParkingType
  case object Any extends ReservedParkingType
  case object RideHailManager extends ReservedParkingType

//  lazy val PricingMap = Map[Int, PricingModel](
//    1 -> Free, 2 -> FlatFee, 3 -> Block
//  )

}
