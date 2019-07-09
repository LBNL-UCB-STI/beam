package beam.utils.beamToVia.apps

import beam.utils.beamToVia.beamEvent.{BeamEvent, BeamPathTraversal, BeamPersonEntersVehicle, BeamPersonLeavesVehicle}
import beam.utils.beamToVia.{BeamEventsReader, Circle, LinkCoordinate, MutableSamplingFilter, Point, Writer}

import scala.collection.mutable

object CollectIds extends App {
  val sourcePath = "D:/Work/BEAM/visualizations/v2.it20.events.bridge_cap_5000.half.csv"

  val networkXml = xml.XML.loadFile("D:/Work/BEAM/visualizations/physSimNetwork.xml")
  val nodes = LinkCoordinate.parseNodes(networkXml)

  val sfCircle = Circle(548966, 4179000, 5000)
  def pointIsInteresting(point: Point): Boolean = point.vithinCircle(sfCircle.x, sfCircle.y, sfCircle.rSquare)

  val interestingNodes = nodes
    .foldLeft(mutable.Map.empty[Int, Point]) {
      case (selectedNodes, (nodeId, point)) if pointIsInteresting(point) => selectedNodes += nodeId -> point
      case (selectedNodes, _)                                            => selectedNodes
    }
    .toMap

  val interestingLinks = LinkCoordinate
    .parseNetwork(networkXml, interestingNodes)
    .foldLeft(mutable.HashSet.empty[Int]) {
      case (links, (linkId, _)) => links += linkId
    }

  /*object AllFilter extends MutableSamplingFilter {
    override def filterAndFix(event: BeamEvent): Seq[BeamEvent] = Seq(event)
  }*/

  object SFCircleFilter extends MutableSamplingFilter {
    var interestingVehicles = mutable.HashSet.empty[String]
    val empty = Seq.empty[BeamEvent]
    override def filterAndFix(event: BeamEvent): Seq[BeamEvent] = event match {
      case pte: BeamPathTraversal =>
        if (pte.linkIds.exists(interestingLinks.contains)) {
          interestingVehicles += pte.vehicleId
          Seq(pte)
        } else {
          empty
        }

      case _ => Seq(event)
    }
  }

  val events = BeamEventsReader
    .fromFileWithFilter(sourcePath, SFCircleFilter)
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
    persons: mutable.HashMap[String, PersonIdInfo] = mutable.HashMap.empty[String, PersonIdInfo],
    vehicleTypes: mutable.HashMap[String, Int] = mutable.HashMap.empty[String, Int],
    vehicleToType: mutable.HashMap[String, String] = mutable.HashMap.empty[String, String]
  )

  def vehicleSelected(vehicleId: String): Boolean = SFCircleFilter.interestingVehicles.contains(vehicleId)

  val accumulator = events.foldLeft(Accumulator())((acc, event) => {
    event match {
      case pte: BeamPathTraversal if vehicleSelected(pte.vehicleId) =>
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

        acc.vehicleToType(pte.vehicleId) = vehicleType

      case plv: BeamPersonLeavesVehicle if vehicleSelected(plv.vehicleId) =>
        acc.persons.get(plv.personId) match {
          case Some(person) => person.leftVehicle += 1
          case None         => acc.persons(plv.personId) = PersonIdInfo(plv.personId, leftVehicle = 1)
        }

        acc.vehicles.get(plv.vehicleId) match {
          case None =>
            acc.vehicles(plv.vehicleId) = VehicleIdInfo(plv.vehicleId, "", mutable.HashSet.empty[String], 0)
          case _ =>
        }

      case pev: BeamPersonEntersVehicle if vehicleSelected(pev.vehicleId) =>
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

  Writer.writeSeqOfString(
    accumulator.persons.values.map(_.toStr(accumulator.vehicleToType)).toSeq.sorted,
    sourcePath + ".persons.txt"
  )
  Console.println("persons written into " + sourcePath + ".persons.txt")

  Writer.writeSeqOfString(
    accumulator.vehicles.values.toArray.sortWith((v1, v2) => v1.vehicleType > v2.vehicleType).map(_.toStr),
    sourcePath + ".vehicles.txt"
  )
  Console.println("vehicles written into " + sourcePath + ".vehicles.txt")

  Writer.writeSeqOfString(
    accumulator.vehicleTypes
      .map {
        case (vType, cnt) => "%09d %s".format(cnt, vType)
        case _            => ""
      }
      .toSeq
      .sorted,
    sourcePath + ".vehicleTypes.txt"
  )
  Console.println("vehicle types written into " + sourcePath + ".vehicleTypes.txt")

  Console.println("done")
}
