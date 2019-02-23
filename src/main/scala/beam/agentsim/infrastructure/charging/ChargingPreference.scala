package beam.agentsim.infrastructure.charging

case class ChargingPreference(pref: Double)

object ChargingPreference {

  private val rand = scala.util.Random

  def Opportunistic = ChargingPreference(0.5d)
  def MustCharge = ChargingPreference(1.0d)
  def Random = ChargingPreference(rand.nextDouble)
}
