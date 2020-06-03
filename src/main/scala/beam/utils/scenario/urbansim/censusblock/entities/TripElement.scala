package beam.utils.scenario.urbansim.censusblock.entities

import beam.utils.scenario.urbansim.censusblock.EntityTransformer

case class TripElement(tripId: String, personId: String, householdId: String, depart: Double, trip_mode: String)

object TripElement extends EntityTransformer[TripElement] {

  def transform(rec: java.util.Map[String, String]): TripElement = {
    val tripId = getIfNotNull(rec, "trip_id")
    val personId = getIfNotNull(rec, "person_id")
    val householdId = getIfNotNull(rec, "household_id")
    val depart = getIfNotNull(rec, "depart").toDouble
    val tripMode = getIfNotNull(rec, "trip_mode")

    TripElement(tripId, personId, householdId, depart, tripMode)
  }
}
