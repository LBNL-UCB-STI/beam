package beam.agentsim.events

/**
  * Base trait for Events defined in Scala.
  *
  * Events need to inherit this trait to avoid errors where BeamEventsWriterCSV complains about unknown attributes.
  */
trait ScalaEvent {}

object ScalaEvent {

  /**
    * Moving all attribute names to here, to avoid duplications here and in events file.
    */
  val ATTRIBUTE_ACTTYPE: String = "actType"
  val ATTRIBUTE_ARRIVAL_TIME: String = "arrivalTime"
  val ATTRIBUTE_CHARGING_TYPE: String = "chargingPointType"
  val ATTRIBUTE_COST: String = "cost"
  val ATTRIBUTE_CURRENT_TOUR_MODE: String = "currentTourMode"
  val ATTRIBUTE_DEPARTURE_TIME: String = "departureTime"
  val ATTRIBUTE_DIRECT_ROUTE_DISTANCE: String = "directRouteDistanceInM"
  val ATTRIBUTE_DIRECT_ROUTE_TIME: String = "directRouteDurationInS"
  val ATTRIBUTE_DRIVER: String = "driver"
  val ATTRIBUTE_DURATION: String = "duration"
  val ATTRIBUTE_EMISSIONS_PROFILE: String = "emissions"
  val ATTRIBUTE_EV_CAV_COUNT: String = "EVCavCount"
  val ATTRIBUTE_EV_NON_CAV_COUNT: String = "EVNonCavCount"
  val ATTRIBUTE_FLEET_ID: String = "fleetId"
  val ATTRIBUTE_FUEL_DELIVERED: String = "fuel"
  val ATTRIBUTE_FROM_STOP_INDEX: String = "fromStopIndex"
  val ATTRIBUTE_LENGTH: String = "length"
  val ATTRIBUTE_LINK_IDS: String = "links"
  val ATTRIBUTE_LINK_TRAVEL_TIME: String = "linkTravelTime"
  val ATTRIBUTE_LOCATION_END_X: String = "endX"
  val ATTRIBUTE_LOCATION_END_Y: String = "endY"
  val ATTRIBUTE_LOCATION_X: String = "x"
  val ATTRIBUTE_LOCATION_Y: String = "y"
  val ATTRIBUTE_MODE: String = "mode"
  val ATTRIBUTE_NON_EV_CAV_COUNT: String = "NonEVCavCount"
  val ATTRIBUTE_NON_EV_NON_CAV_COUNT: String = "NonEVNonCavCount"
  val ATTRIBUTE_NUM_PASS: String = "numPassengers"
  val ATTRIBUTE_OFFERED_PICKUP_TIME: String = "offeredPickupTime"
  val ATTRIBUTE_PARKING_TAZ: String = "parkingTaz"
  val ATTRIBUTE_PARKING_TYPE: String = "parkingType"
  val ATTRIBUTE_PARKING_ZONE_ID: String = "parkingZoneId"
  val ATTRIBUTE_PERSON: String = "person"
  val ATTRIBUTE_PRICING_MODEL: String = "pricingModel"
  val ATTRIBUTE_PRIMARY_FUEL_LEVEL: String = "primaryFuelLevel"
  val ATTRIBUTE_PRIMARY_FUEL_TYPE: String = "primaryFuelType"
  val ATTRIBUTE_QUOTED_WAIT_TIME: String = "quotedWaitTimeInS"
  val ATTRIBUTE_REQUESTED_PICKUP_TIME: String = "requestedPickupTime"
  val ATTRIBUTE_RESERVATION_ERROR_CODE: String = "errorCode"
  val ATTRIBUTE_RESERVATION_TIME: String = "reservationTime"
  val ATTRIBUTE_RESERVATION_TYPE: String = "reservationType"
  val ATTRIBUTE_RIDERS: String = "riders"
  val ATTRIBUTE_SCORE: String = "score"
  val ATTRIBUTE_SECONDARY_FUEL_LEVEL: String = "secondaryFuelLevel"
  val ATTRIBUTE_SECONDARY_FUEL_TYPE: String = "secondaryFuelType"
  val ATTRIBUTE_SEATING_CAPACITY: String = "seatingCapacity"
  val ATTRIBUTE_SHIFT_EVENT_TYPE: String = "shiftEventType"
  val ATTRIBUTE_SHIFT_STATUS: String = "shiftStatus"
  val ATTRIBUTE_STORAGE_CAPACITY_IN_JOULES: String = "storageCapacityInJoules"
  val ATTRIBUTE_STORED_ELECTRICITY_IN_JOULES: String = "storedElectricityInJoules"
  val ATTRIBUTE_TOLL_PAID: String = "tollPaid"
  val ATTRIBUTE_TO_STOP_INDEX: String = "toStopIndex"
  val ATTRIBUTE_VEHICLE: String = "vehicle"
  val ATTRIBUTE_VEHICLE_CAPACITY: String = "capacity"
  val ATTRIBUTE_VEHICLE_TYPE: String = "vehicleType"
  val ATTRIBUTE_WHEELCHAIR_REQUIREMENT: String = "wheelchairRequirement"
}
