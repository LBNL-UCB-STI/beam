package scripts.beam_to_matsim

import beam.utils.beam_to_matsim.events.{BeamEvent, BeamPersonEntersVehicle}
import beam.utils.beam_to_matsim.io.{BeamEventsReader, Writer}

import scala.collection.mutable
import scala.io.Source

/*
a script to find all persons which uses specific vehicles
 */

object FindPersonsUsesVehicles extends App {

  // format: off
  /******************************************************************************************************
    ./gradlew execute -PmainClass=beam.utils.beam_to_matsim.scripts.FindPersonsUsesVehicles -PappArgs="[
      '<beam events csv file>',
      '<vehicles output file>',
      '<text file containing vehicles ids>'
    ]" -PmaxRAM=16g
  *******************************************************************************************************/
  // format: on

  val eventsPath = args(0)
  val outputPath = args(1)
  val vehiclesIdsFilePath = args(2)

  def find(vehiclesIdsFilePath: String, eventsFilePath: String): Iterable[String] = {
    val vehicleIds = mutable.HashSet.empty[String]
    val idsSource = Source fromFile vehiclesIdsFilePath
    idsSource.getLines().foreach(vehicleIds += _)
    idsSource.close()

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

  val personsIds: Iterable[String] = find(vehiclesIdsFilePath, eventsPath)

  Writer.writeSeqOfString(personsIds, outputPath)
}
