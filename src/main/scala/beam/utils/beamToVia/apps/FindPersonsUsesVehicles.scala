package beam.utils.beamToVia.apps

import beam.utils.beamToVia.beamEvent.{BeamEvent, BeamPersonEntersVehicle}
import beam.utils.beamToVia.{BeamEventsReader, Writer}

import scala.collection.mutable
import scala.io.Source

object FindPersonsUsesVehicles extends App {

  val personsIds: Iterable[String] = find(
    "D:/Work/BEAM/visualizations/v1.0.events.bridge_cap_5000.csv.RHids.txt",
    "D:/Work/BEAM/visualizations/v1.0.events.bridge_cap_5000.csv"
  )

  Writer.writeSeqOfString(personsIds, "D:/Work/BEAM/visualizations/v1.0.events.bridge_cap_5000.csv.RHUsersIds.txt")

  def find(vehiclesIdsFilePath: String, eventsFilePath: String): Iterable[String] = {
    val vehicleIds = mutable.HashSet.empty[String]
    val idsSource = Source fromFile vehiclesIdsFilePath
    idsSource.getLines().foreach(vehicleIds += _)
    idsSource.close()

    val notADriver = (id: String) => {
      !id.contains("rideHailAgent") && !id.contains("TransitDriverAgent")
    }

    def foldLeftFunc(acc: mutable.HashSet[String], event: BeamEvent): mutable.HashSet[String] = {
      event match {
        case pev: BeamPersonEntersVehicle =>
          if (vehicleIds.contains(pev.vehicleId)) acc += pev.personId else acc
        case _ => acc
      }
    }

    val emptyHS = mutable.HashSet.empty[String]
    val personsIds = BeamEventsReader
      .fromFileFoldLeft[mutable.HashSet[String]](eventsFilePath, emptyHS, foldLeftFunc)
      .getOrElse(emptyHS)

    personsIds
  }
}
