package beam.utils.csv.writers

import scala.language.implicitConversions

import beam.utils.scenario.{HouseholdInfo, PersonInfo, VehicleInfo}
import com.typesafe.scalalogging.LazyLogging

object BeamCsvScenarioWriter extends LazyLogging {

  implicit class PopulationWriter(arr: Array[PersonInfo]) {
    def this(elements: Iterable[PersonInfo]) {
      this(elements.toArray)
    }

    def writeCsvPersonsFile(outputFileStr: String): Array[PersonInfo] = {
      PopulationCsvWriter.toCsv(arr.toIterator, outputFileStr)
      arr
    }
  }

  implicit class VehicleWriter(arr: Array[VehicleInfo]) {
    def this(elements: Iterable[VehicleInfo]) {
      this(elements.toArray)
    }

    def writeCsvHouseholdsFile(outputFileStr: String): Array[VehicleInfo] = {
      VehiclesCsvWriter(arr.toIterable).toCsv(arr.toIterator, outputFileStr)
      arr
    }
  }

  implicit class HouseholdWriter(arr: Array[HouseholdInfo]) {
    def this(elements: Iterable[HouseholdInfo]) {
      this(elements.toArray)
    }

    def writeCsvHouseholdsFile(outputFileStr: String): Array[HouseholdInfo] = {
      PopulationCsvWriter.toCsv(arr.toIterator, outputFileStr)
      arr
    }
  }

}
