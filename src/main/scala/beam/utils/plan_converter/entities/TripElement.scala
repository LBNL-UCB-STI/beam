package beam.utils.plan_converter.entities

import beam.utils.plan_converter.EntityTransformer

case class TripElement(tripId: Int, personId: Int, householdId: Int, depart: Double, trip_mode: String)

object TripElement extends EntityTransformer[TripElement] {

  def transform(rec: java.util.Map[String, String]): TripElement = {
    val tripId = getIfNotNull(rec, "trip_id").toInt
    val personId = getIfNotNull(rec, "person_id").toInt
    val householdId = getIfNotNull(rec, "household_id").toInt
    val depart = getIfNotNull(rec, "depart").toDouble
    val tripMode = getIfNotNull(rec, "trip_mode")

    TripElement(tripId, personId, householdId, depart, tripMode)
  }
}
