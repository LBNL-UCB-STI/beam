package beam.agentsim.infrastructure.power

case class PhysicalBounds(minPower: Double, maxPower: Double, price: Double)

object PhysicalBounds {
  def default(power: Double): PhysicalBounds = PhysicalBounds(power, power, 0)
}
