package beam.agentsim.infrastructure

import beam.agentsim.Resource
import beam.agentsim.infrastructure.ParkingStall.StallAttributes
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.EmbodiedBeamLeg
import org.matsim.api.core.v01.Id


class ParkingStall(val id: Id[ParkingStall], val attributes: StallAttributes, val location: Location, val cost: Double) extends Resource[ParkingStall] {
  override def getId: Id[ParkingStall] = id
}

object ParkingStall{
  case class StallAttributes(val tazId: Id[TAZ], val parkingType: ParkingType, val pricingModel: PricingModel, val chargingType: ChargingType, val fee: Double, val parkingId: Option[Id[StallAttributes]])

  sealed trait ParkingType
  case object Residential extends ParkingType
  case object Workplace   extends ParkingType
  case object Public      extends ParkingType
  case object NoOtherExists extends ParkingType

  lazy val parkingMap = Map[Int, ParkingType](
    1 -> Residential, 2 -> Workplace, 3 -> Public, 4 -> NoOtherExists
  )

  sealed trait ChargingType
  case object NoCharger   extends ChargingType
  case object Level1      extends ChargingType
  case object Level2      extends ChargingType
  case object DCFast      extends ChargingType
  case object UltraFast   extends ChargingType

  lazy val chargingMap = Map[Int, ChargingType](
    1 -> NoCharger, 2 -> Level1, 3 -> Level2, 4 -> DCFast, 5 -> UltraFast
  )

  sealed trait ChargingPreference
  case object NoNeed          extends ChargingPreference
  case object MustCharge      extends ChargingPreference
  case object Opportunistic   extends ChargingPreference

  /*
   *  Flat fee means one price is paid independent of time
   *  Block means price is hourly and can change with the amount of time at the spot (e.g. first hour $1, after than $2/hour)
   *
   *  Use block price even if price is a simple hourly price.
   */
  sealed trait PricingModel
  case object Free     extends PricingModel
  case object FlatFee  extends PricingModel
  case object Block    extends PricingModel

  lazy val PricingMap = Map[Int, PricingModel](
    1 -> Free, 2 -> FlatFee, 3 -> Block
  )

}