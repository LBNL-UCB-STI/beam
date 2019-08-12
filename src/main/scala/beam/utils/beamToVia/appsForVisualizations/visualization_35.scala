package beam.utils.beamToVia.appsForVisualizations

import beam.utils.beamToVia.beamEvent.BeamPathTraversal
import beam.utils.beamToVia.beamEventsFilter.{MutableVehiclesFilter, VehicleTrip}
import beam.utils.beamToVia.viaEvent.ViaEvent
import beam.utils.beamToVia.{EventsProcessor, Writer}

import scala.collection.mutable

import beam.utils.beamToVia.appsForVisualizations.{visualization_35_person1_alternatives => person1}

object visualization_35 extends App {
  val dirPath = "D:/Work/BEAM/visualizations/vis35/"
  val fileName = "2.events.csv"

  val beamEventsFilePath = dirPath + fileName
  val viaEventsFile = beamEventsFilePath + ".all.via.xml"
  val viaIdFile = beamEventsFilePath + ".all.ids.txt"

  object Selector extends MutableVehiclesFilter.SelectNewVehicle {
    override def select(vehicleMode: String, vehicleType: String, vehicleId: String): Boolean = {
//      if (person1.vehicleIds.contains(vehicleId)) {
//        false
//      } else {
      vehicleMode match {
        case "CAR" | "BUS" => true
        case _             => false
      }
//      }
    }
  }

  val eventsWithAlternatives = {
    val (vehiclesEvents, _) = EventsProcessor.readWithFilter(beamEventsFilePath, MutableVehiclesFilter(Selector))

    def alternativePathToVehiclesTrips(path: Seq[BeamPathTraversal], idPrefix: String): Iterable[VehicleTrip] = {
      val trips = mutable.Map.empty[String, VehicleTrip]
      path.foreach(
        pte => {
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
        }
      )

      trips.values
    }

    val alt0 = alternativePathToVehiclesTrips(person1.alternative0_ride_hail, "_alt0_")
    val alt1 = alternativePathToVehiclesTrips(person1.alternative1_ride_hail_pooled, "_alt1_")
    val alt2 = alternativePathToVehiclesTrips(person1.alternative2_bike, "_alt2_")
    val alt3 = alternativePathToVehiclesTrips(person1.alternative3_walk_transit_bike_bus, "_alt3_")
    val alt4 = alternativePathToVehiclesTrips(person1.alternative4_walk, "_alt4_")
    val alt5 = alternativePathToVehiclesTrips(person1.alternative5_walk_transit_bus, "_alt5_")

    vehiclesEvents // ++ alt0 ++ alt1 ++ alt2 ++ alt3 ++ alt4 ++ alt5
  }

  def vehicleType(pte: BeamPathTraversal): String = pte.mode + "_P%03d".format(pte.numberOfPassengers)
  def vehicleId(pte: BeamPathTraversal): String = vehicleType(pte) + "__" + pte.vehicleId

  val (events, typeToId) = EventsProcessor.transformPathTraversals(eventsWithAlternatives, vehicleId, vehicleType)

  Writer.writeViaEventsQueue[ViaEvent](events, _.toXml.toString, viaEventsFile)
  Writer.writeViaIdFile(typeToId, viaIdFile)

}
