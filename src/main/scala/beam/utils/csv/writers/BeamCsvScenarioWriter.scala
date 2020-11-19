package beam.utils.csv.writers

import scala.language.implicitConversions

import beam.utils.scenario.{HouseholdInfo, PersonInfo, VehicleInfo}

/*
 * This file keeps symmetry with BeamCsvScenarioReader. Examples below:
 *   import beam.utils.csv.readers.BeamCsvScenarioReader
 *   import beam.utils.csv.writers.BeamCsvScenarioWriter._
 *
 *   val vehicles: Iterable[VehicleInfo] =
 *     BeamCsvScenarioReader.readVehiclesFile("vehicles.csv")
 *   vehicles.writeCsvVehiclesFile("vehicles_output.csv")
 *
 *   val households: Array[HouseholdInfo] =
 *     BeamCsvScenarioReader.readHouseholdsFile("householdsFile.csv", vehicles)
 *   vehicles.writeCsvVehiclesFile("households_output.csv")
 *
 *  val persons: Array[PersonInfo] =
 *    BeamCsvScenarioReader.readPersonsFile("persons.csv")
 *  persons.writeCsvPersonsFile("persons_out.csv")
 */
object BeamCsvScenarioWriter {

  implicit class PopulationWriter(arr: Array[PersonInfo]) {

    def writeCsvPersonsFile(outputFileStr: String): Array[PersonInfo] = {
      PopulationCsvWriter.toCsv(arr.toIterator, outputFileStr)
      arr
    }
  }
  implicit def toPopulationWriter(elements: Iterable[PersonInfo]): PopulationWriter = {
    new PopulationWriter(elements.toArray)
  }

  implicit class VehicleWriter(arr: Array[VehicleInfo]) {

    def writeCsvVehiclesFile(outputFileStr: String): Array[VehicleInfo] = {
      VehiclesCsvWriter(arr.toIterable).toCsv(arr.toIterator, outputFileStr)
      arr
    }
  }
  implicit def toVehicleWriter(elements: Iterable[VehicleInfo]): VehicleWriter = {
    new VehicleWriter(elements.toArray)
  }

  implicit class HouseholdWriter(arr: Array[HouseholdInfo]) {

    def writeCsvHouseholdsFile(outputFileStr: String): Array[HouseholdInfo] = {
      PopulationCsvWriter.toCsv(arr.toIterator, outputFileStr)
      arr
    }
  }
  implicit def toHouseholdWriter(elements: Iterable[HouseholdInfo]): HouseholdWriter = {
    new HouseholdWriter(elements.toArray)
  }

}
