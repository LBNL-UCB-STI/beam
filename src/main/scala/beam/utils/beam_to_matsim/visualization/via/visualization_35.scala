package beam.utils.beam_to_matsim.visualization.via

import beam.utils.beam_to_matsim.events.BeamPathTraversal
import beam.utils.beam_to_matsim.events_filter.{MutableVehiclesFilter, VehicleTrip}
import beam.utils.beam_to_matsim.io.{Reader, Writer}
import beam.utils.beam_to_matsim.via_event.ViaEvent

import scala.collection.mutable

object visualization_35 extends App {
  val dirPath = "D:/Work/BEAM/visualizations/vis35/"
  val fileName = "2.events.csv"

  val beamEventsFilePath = dirPath + fileName
  val viaEventsFile = beamEventsFilePath + ".all.via.xml"
  val viaIdFile = beamEventsFilePath + ".all.ids.txt"

  object Selector extends MutableVehiclesFilter.SelectNewVehicle {

    override def select(vehicleMode: String, vehicleType: String, vehicleId: String): Boolean = {
      vehicleMode match {
        case "CAR" | "BUS" => true
        case _             => false
      }
    }
  }

  val eventsWithAlternatives = {
    val (vehiclesEvents, _) = Reader.readWithFilter(beamEventsFilePath, MutableVehiclesFilter(Selector))

    def alternativePathToVehiclesTrips(path: Seq[BeamPathTraversal], idPrefix: String): Iterable[VehicleTrip] = {
      val trips = mutable.Map.empty[String, VehicleTrip]
      path.foreach(pte => {
        val vId = idPrefix + pte.vehicleId
        val pteWithCorrectVehicleId = new BeamPathTraversal(
          pte.time,
          vId,
          pte.driverId,
          pte.vehicleType,
          pte.mode,
          pte.numberOfPassengers,
          pte.arrivalTime,
          pte.linkIds,
          pte.linkTravelTime
        )

        trips.get(vId) match {
          case Some(trip) => trip.trip += pteWithCorrectVehicleId
          case None       => trips(vId) = VehicleTrip(vId, pteWithCorrectVehicleId)
        }
      })

      trips.values
    }

    val alt0 = alternativePathToVehiclesTrips(visualization_35_person1_alternatives.alternative0_ride_hail, "_alt0_")
    val alt1 =
      alternativePathToVehiclesTrips(visualization_35_person1_alternatives.alternative1_ride_hail_pooled, "_alt1_")
    val alt2 = alternativePathToVehiclesTrips(visualization_35_person1_alternatives.alternative2_bike, "_alt2_")
    val alt3 =
      alternativePathToVehiclesTrips(visualization_35_person1_alternatives.alternative3_walk_transit_bike_bus, "_alt3_")
    val alt4 = alternativePathToVehiclesTrips(visualization_35_person1_alternatives.alternative4_walk, "_alt4_")
    val alt5 =
      alternativePathToVehiclesTrips(visualization_35_person1_alternatives.alternative5_walk_transit_bus, "_alt5_")

    vehiclesEvents // ++ alt0 ++ alt1 ++ alt2 ++ alt3 ++ alt4 ++ alt5
  }

  def vehicleType(pte: BeamPathTraversal): String = pte.mode + "_P%03d".format(pte.numberOfPassengers)
  def vehicleId(pte: BeamPathTraversal): String = vehicleType(pte) + "__" + pte.vehicleId

  val (events, typeToId) = Reader.transformPathTraversals(eventsWithAlternatives, vehicleId, vehicleType)

  Writer.writeViaEventsQueue[ViaEvent](events, _.toXml.toString, viaEventsFile)
  Writer.writeViaIdFile(typeToId, viaIdFile)

}
