package beam.utils.beam_to_matsim.utils

import beam.utils.beam_to_matsim.io.{BeamEventsReader, HashSetReader, Writer}
import beam.utils.beam_to_matsim.events.{BeamEvent, BeamPathTraversal}

import scala.collection.mutable

/*
a script to collect vehicle types ids inside and outside of specified circle.
 */

object CollectIds extends App {

  val sourceFileName = "v34.it30.events.third.csv"

  val dirPath = "D:/Work/BEAM/visualizations/"
  val sourcePath = dirPath + sourceFileName
  val outputPath = dirPath + "collectedIds/" + sourceFileName

  // file was generated with FindIdsInCircles script
  val vehiclesInCircle = HashSetReader.fromFile(dirPath + "v34.it30.events.third.csv.in_SF.vehicles.txt")

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
    .fromFileFoldLeft[Accumulator](
      sourcePath,
      new Accumulator(),
      (acc, event) => {
        acc.process(event)
        acc
      }
    )
    .getOrElse(new Accumulator())

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
