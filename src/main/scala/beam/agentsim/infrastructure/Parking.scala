package beam.agentsim.infrastructure

class ParkingCapacity {
  val PARKING_CAPACITY = "CAPACITY"
  val HOURLY_RATE = "HOURLY_RATE"
  val CHARGING_LEVEL = "CHARGING_LEVEL"
}

object ParkingType {
  val OFF_STREET_PARKING = "OP"
  val ON_STREET_PARKING = "SP"
  val PARKING_WITH_CHARGER = "EP"
}

// TODO: refactor this away from strings to classes/objects/enums?
// TODO: refine levels as needed by current main application
object ChargerLevel {
  val L2 = "L2"
  val L3 = "L3"
}

// TODO: refine levels as needed by current main application
object ChargerPower {
  def getChargingPowerInkW(chargerLevel: String): Double = chargerLevel match {
    case ChargerLevel.L2 => 3.3
    case ChargerLevel.L3 => 120.0
    case _               => throw new IllegalArgumentException("unknown charger level")
  }
}
