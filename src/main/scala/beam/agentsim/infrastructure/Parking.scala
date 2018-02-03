package beam.agentsim.infrastructure

object Parking {
  val PARKING_MANAGER = "PARKING_MANAGER"
  val PARKING_TYPE = "PARKING_TYPE"
  val PARKING_CAPACITY = "PARKING_CAPACITY"
  val HOURLY_RATE = "HOURLY_RATE"
  val CHARGING_LEVEL = "CHARGING_LEVEL"
}

object ParkingType {
  val OFF_STREET_PARKING = "OFF_STREET_PARKING"
  val ON_STREET_PARKING = "STREET_PARKING"
  val PARKING_WITH_CHARGER = "PARKING_WITH_CHARGER"
}

// TODO: refactor this away from strings to classes/objects/enums?
// TODO: refine levels as needed by current main application
object ChargerLevel {
  val L2 = "L2"
  val L3 = "L3"
}

// TODO: refine levels as needed by current main application
object ChargerPower {
  def getChargingPowerInkW(chargerLevel: String): Double ={
    chargerLevel match {
    case ChargerLevel.L2 => 3.3
    case ChargerLevel.L3 => 120.0
    case _ => - 1.0
    throw new IllegalArgumentException ("unknown charger level")
  }
  }
}