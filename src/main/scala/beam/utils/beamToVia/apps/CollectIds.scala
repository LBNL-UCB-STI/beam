package beam.utils.beamToVia.apps

import beam.utils.beamToVia.{BeamEventsReader, HashSetReader, Writer}
import beam.utils.beamToVia.beamEvent.{BeamEvent, BeamPathTraversal, BeamPersonEntersVehicle, BeamPersonLeavesVehicle}
import beam.utils.beamToVia.beamEventsFilter.MutableSamplingFilter

import scala.collection.mutable

object CollectIds extends App {
  val dirPath = "D:/Work/BEAM/visualizations/"

  val sourcePath = dirPath + "v33.it20.events.csv"
  val outputPath = dirPath + "collectedIds/v33.it20.events.csv"

  val vehiclesInCircle = HashSetReader.fromFile(dirPath + "v33.it20.events.in_SF.vehicles.txt")

  case class PersonIdInfo(
    id: String,
    var enteredVehicle: Int = 0,
    var leftVehicle: Int = 0,
    var hasBeenDriver: Int = 0,
    usedVehicles: mutable.HashSet[String] = mutable.HashSet.empty[String]
  ) {

    def toStr: String =
      "entered: % 3d left: % 3d beenDriver: % 3d   id: %9s   ".format(enteredVehicle, leftVehicle, hasBeenDriver, id)

    def toStr(vehiclesMap: mutable.HashMap[String, String]): String = {
      val usedVehicleTypes = usedVehicles.map(vId => vehiclesMap.getOrElse(vId, "")).toSeq.sorted
      "%02d entered: % 3d left: % 3d beenDriver: % 3d   id: %9s  %s :: %s".format(
        usedVehicleTypes.size,
        enteredVehicle,
        leftVehicle,
        hasBeenDriver,
        id,
        usedVehicleTypes.mkString(" "),
        usedVehicles.mkString(" ")
      )
    }
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

  def vehicleType(pte: BeamPathTraversal): String =
    pte.mode + "_" + pte.vehicleType + "_P%03d".format(pte.numberOfPassengers)

  def vehicleId(pte: BeamPathTraversal): String =
    vehicleType(pte) + "__" + pte.vehicleId

  class Accumulator {
    val vehicleTypes = mutable.HashMap.empty[String, Int]
    val vehicleTypesOutOfCircle = mutable.HashMap.empty[String, Int]
    // val vehicles = mutable.HashMap.empty[String, VehicleIdInfo]
    // val vehiclesTypeToIds = mutable.HashMap.empty[String, mutable.HashSet[String]]
    // val persons = mutable.HashMap.empty[String, PersonIdInfo]
    // val vehicleToType = mutable.HashMap.empty[String, String]

    def process(event: BeamEvent): Unit = {
      event match {
        case pte: BeamPathTraversal if vehiclesInCircle.contains(pte.vehicleId) =>
          val vType = pte.mode + "____" + pte.vehicleType
          vehicleTypes.get(vType) match {
            case Some(cnt) => vehicleTypes(vType) = cnt + 1
            case None      => vehicleTypes(vType) = 1
          }

        case pte: BeamPathTraversal =>
          val vType = pte.mode + "____" + pte.vehicleType
          vehicleTypesOutOfCircle.get(vType) match {
            case Some(cnt) => vehicleTypesOutOfCircle(vType) = cnt + 1
            case None      => vehicleTypesOutOfCircle(vType) = 1
          }

        case _ =>
      }
    }
  }

  val accumulator = BeamEventsReader
    .fromFileFoldLeft[Accumulator](sourcePath, new Accumulator(), (acc, event) => {
      acc.process(event)
      acc
    })
    .getOrElse(new Accumulator())

  /* val accumulator = events.foldLeft(Accumulator())((acc, event) => {
    if (event.time <= 27000) acc
    else
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

          val vehicleType = pte.mode + "__" + pte.vehicleType
          acc.vehicleTypes.get(vehicleType) match {
            case Some(cnt) => acc.vehicleTypes(vehicleType) = cnt + 1
            case None      => acc.vehicleTypes(vehicleType) = 1
          }

          acc.vehiclesTypeToIds.get(pte.vehicleType) match {
            case Some(vehicleIds) => vehicleIds += pte.vehicleId
            case None             => acc.vehiclesTypeToIds(pte.vehicleType) = mutable.HashSet(pte.vehicleId)
          }

          acc.vehicleToType(pte.vehicleId) = vehicleType

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
            case Some(person) =>
              person.enteredVehicle += 1
              person.usedVehicles += pev.vehicleId
            case None =>
              acc.persons(pev.personId) =
                PersonIdInfo(pev.personId, enteredVehicle = 1, usedVehicles = mutable.HashSet(pev.vehicleId))
          }

          acc.vehicles.get(pev.vehicleId) match {
            case None =>
              acc.vehicles(pev.vehicleId) = VehicleIdInfo(pev.vehicleId, "", mutable.HashSet.empty[String], 0)
            case _ =>
          }

        case _ =>
      }

    acc
  })

  accumulator.vehiclesTypeToIds.foreach {
    case (vehicleType, typeIds) =>
      val vtOutPath = outputPath + "." + vehicleType + ".ids.txt"
      Writer.writeSeqOfString(typeIds, vtOutPath)
      Console.println("ids of type " + vehicleType + " written into " + vtOutPath)
  }

  Writer.writeSeqOfString(
    accumulator.persons.values.map(_.toStr(accumulator.vehicleToType)).toSeq.sorted,
    outputPath + ".persons.txt"
  )
  Console.println("persons written into " + outputPath + ".persons.txt")

  Writer.writeSeqOfString(
    accumulator.vehicles.values.toArray.sortWith((v1, v2) => v1.vehicleType > v2.vehicleType).map(_.toStr),
    outputPath + ".vehicles.txt"
  )
  Console.println("vehicles written into " + outputPath + ".vehicles.txt")
   */

  Writer.writeSeqOfString(
    accumulator.vehicleTypes
      .map {
        case (vType, cnt) => "%09d %s".format(cnt, vType)
        case _            => ""
      }
      .toSeq
      .sorted,
    outputPath + ".vehicleTypes.txt"
  )
  Console.println("vehicle types written into " + outputPath + ".vehicleTypes.txt")

  Writer.writeSeqOfString(
    accumulator.vehicleTypesOutOfCircle
      .map {
        case (vType, cnt) => "%09d %s".format(cnt, vType)
        case _            => ""
      }
      .toSeq
      .sorted,
    outputPath + ".vehicleTypes.OOC.txt"
  )
  Console.println("vehicle types out of circle written into " + outputPath + ".vehicleTypes.txt")

  Console.println("done")
}
