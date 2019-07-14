package beam.utils.beamToVia.apps

import beam.utils.beamToVia.{BeamEventsReader, HashSetReader, Writer}
import beam.utils.beamToVia.beamEvent.{BeamEvent, BeamPathTraversal, BeamPersonEntersVehicle, BeamPersonLeavesVehicle}
import beam.utils.beamToVia.beamEventsFilter.MutableSamplingFilter

import scala.collection.mutable

object CollectIds extends App {
  val sourcePath = "D:/Work/BEAM/visualizations/v2.it20.events.bridge_cap_5000.half.csv"
  val outputPath = "D:/Work/BEAM/visualizations/collectedIds/v2.it20.events.bridge_cap_5000.half2.csv"

  val personsInCircle = HashSetReader.fromFile("D:/Work/BEAM/visualizations/v2.it20.events.bridge_cap_5000.half_in_SF.persons.txt")
  val vehiclesInCircle = HashSetReader.fromFile("D:/Work/BEAM/visualizations/v2.it20.events.bridge_cap_5000.half_in_SF.vehicles.txt")

/*  object Filter extends MutableSamplingFilter {
    private val empty = Seq.empty[BeamEvent]
    override def filter(event: BeamEvent): Seq[BeamEvent] = {
      event match {
        case pte: BeamPathTraversal =>
          if (vehiclesInCircle.contains(pte.vehicleId)) Seq(event)
          else empty

        case pev: BeamPersonEntersVehicle =>
          if (personsInCircle.contains(pev.personId) && vehiclesInCircle.contains(pev.vehicleId)) Seq(event)
          else empty

        case plv: BeamPersonLeavesVehicle =>
          if (personsInCircle.contains(plv.personId) && vehiclesInCircle.contains(plv.vehicleId)) Seq(event)
          else empty

        case _ => empty
      }
    }
  }

  val events = BeamEventsReader
    .fromFileWithFilter(sourcePath, Filter)
    .getOrElse(Seq.empty[BeamEvent])

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

  case class Accumulator(
    vehicles: mutable.HashMap[String, VehicleIdInfo] = mutable.HashMap.empty[String, VehicleIdInfo],
    vehiclesTypeToIds: mutable.HashMap[String, mutable.HashSet[String]] =
      mutable.HashMap.empty[String, mutable.HashSet[String]],
    persons: mutable.HashMap[String, PersonIdInfo] = mutable.HashMap.empty[String, PersonIdInfo],
    vehicleTypes: mutable.HashMap[String, Int] = mutable.HashMap.empty[String, Int],
    vehicleToType: mutable.HashMap[String, String] = mutable.HashMap.empty[String, String]
  )

  val accumulator = events.foldLeft(Accumulator())((acc, event) => {
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

  Console.println("done")*/
}
