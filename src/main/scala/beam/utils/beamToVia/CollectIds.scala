package beam.utils.beamToVia

import beam.utils.beamToVia.beamEvent.{BeamEvent, BeamPathTraversal, BeamPersonEntersVehicle, BeamPersonLeavesVehicle}

import scala.collection.mutable

object CollectIds extends App {
  val sourcePath = "D:/Work/BEAM/Via-fs-light/2.events.xml" // 2.events.csv

  val events = EventsReader
    .fromFile(sourcePath)
    .getOrElse(Seq.empty[BeamEvent])

  case class PersonIdInfo(
    id: String,
    var enteredVehicle: Int = 0,
    var leftVehicle: Int = 0,
    var hasBeenDriver: Int = 0
  ) {

    def toStr: String =
      "entered: % 3d left: % 3d beenDriver: % 3d   id: %s   ".format(enteredVehicle, leftVehicle, hasBeenDriver, id)
  }
  case class VehicleIdInfo(
    id: String,
    var vehicleType: String,
    vehicleMode: mutable.HashSet[String],
    var appearedInPathTraversal: Int
  ) {

    def toStr: String =
      "type: %s  mode: %s appears: % 3d    id: %s".format(
        vehicleType,
        vehicleMode.mkString(","),
        appearedInPathTraversal,
        id
      )
  }

  case class Accumulator(
    vehicles: mutable.Map[String, VehicleIdInfo] = mutable.Map.empty[String, VehicleIdInfo],
    persons: mutable.Map[String, PersonIdInfo] = mutable.Map.empty[String, PersonIdInfo]
  )

  val accumulator = events.foldLeft(Accumulator())((acc, event) => {
    event match {
      case pte: BeamPathTraversal =>
        acc.persons.get(pte.driverId) match {
          case Some(person) => person.hasBeenDriver += 1
          case None         => acc.persons(pte.driverId) = PersonIdInfo(pte.driverId, hasBeenDriver = 1)
        }
        acc.vehicles.get(pte.vehicleId) match {
          case Some(vehicle) =>
            if (!vehicle.vehicleMode.contains(pte.mode)) vehicle.vehicleMode += pte.mode
            if (vehicle.vehicleType.isEmpty) vehicle.vehicleType = pte.vehicleType
            vehicle.appearedInPathTraversal += 1

          case None =>
            acc.vehicles(pte.vehicleId) = VehicleIdInfo(pte.vehicleId, pte.mode, mutable.HashSet(pte.vehicleType), 1)
        }

      case plv: BeamPersonLeavesVehicle =>
        acc.persons.get(plv.personId) match {
          case Some(person) => person.leftVehicle += 1
          case None         => acc.persons(plv.personId) = PersonIdInfo(plv.personId, leftVehicle = 1)
        }
        acc.vehicles.get(plv.vehicleId) match {
          case None =>
            acc.vehicles(plv.vehicleId) = VehicleIdInfo(plv.vehicleId, "", mutable.HashSet.empty[String], 0)
          case _ =>
        }

      case pev: BeamPersonEntersVehicle =>
        acc.persons.get(pev.personId) match {
          case Some(person) => person.enteredVehicle += 1
          case None         => acc.persons(pev.personId) = PersonIdInfo(pev.personId, enteredVehicle = 1)
        }
        acc.vehicles.get(pev.vehicleId) match {
          case None =>
            acc.vehicles(pev.vehicleId) = VehicleIdInfo(pev.vehicleId, "", mutable.HashSet.empty[String], 0)
          case _ =>
        }

    }

    acc
  })

  Writer.writeSeqOfString(
    accumulator.persons.values.toArray.sortWith((p1, p2) => p1.id > p2.id).map(_.toStr),
    sourcePath + ".persons.txt"
  )
  Writer.writeSeqOfString(
    accumulator.vehicles.values.toArray.sortWith((v1, v2) => v1.vehicleType > v2.vehicleType).map(_.toStr),
    sourcePath + ".vehicles.txt"
  )
}
