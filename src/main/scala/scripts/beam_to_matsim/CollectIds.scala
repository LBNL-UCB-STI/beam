package scripts.beam_to_matsim

import beam.utils.beam_to_matsim.events.{BeamEvent, BeamPathTraversal}
import beam.utils.beam_to_matsim.io.{BeamEventsReader, HashSetReader, Writer}

import scala.collection.mutable

/*
a script to collect vehicle types ids inside and outside of specified circle.
 */

object CollectIds extends App {

  // format: off
  /*****************************************************************************************
    ./gradlew execute -PmainClass=beam.utils.beam_to_matsim.scripts.CollectIds -PappArgs="[
      '<beam events csv file>',
      '<output directory path>',
      '<text file generated with FindIdsInCircles script>'
    ]" -PmaxRAM=16g
  *****************************************************************************************/
  // format: on

  val eventsPath = args(0)
  val outputPath = args(1)
  val idsInCircles = args(2) // file was generated with FindIdsInCircles script

  val vehiclesInCircle = HashSetReader.fromFile(idsInCircles)

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
      eventsPath,
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
    outputPath + "/vehicleTypes.txt"
  )
  Console.println("vehicle types written into " + outputPath + "/vehicleTypes.txt")

  Writer.writeSeqOfString(
    accumulator.vehicleTypesOutOfCircle
      .map {
        case (vType, cnt) => "%09d %s".format(cnt, vType)
        case _            => ""
      }
      .toSeq
      .sorted,
    outputPath + "/vehicleTypes.OOC.txt"
  )
  Console.println("vehicle types out of circle written into " + outputPath + "/vehicleTypes.txt")
}
