package beam.agentsim.infrastructure.parking.charging

sealed trait ChargingPreference

object ChargingPreference {

  case class ChargingPreference(pref: Double)

  private val rand = scala.util.Random

  def Opportunistic = ChargingPreference(0.5d)
  def MustCharge = ChargingPreference(1.0d)
  def Random = ChargingPreference(rand.nextDouble)
}
