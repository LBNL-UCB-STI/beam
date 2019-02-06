package beam.agentsim.infrastructure.parking

sealed trait PricingModel
object PricingModel {
  case object FlatFee extends PricingModel
  case object Block extends PricingModel

  def apply(s: String): PricingModel = s match {
    case "FlatFee" => FlatFee
    case "Block"   => Block
  }
}
