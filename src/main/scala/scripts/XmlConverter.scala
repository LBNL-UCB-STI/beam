package scripts
import java.io.File

import beam.utils.FileUtils

object XmlConverter extends App {
  val populationXml = new File("test/input/beamville/population.xml")
  val populationCsv = new File("test/input/beamville/population.csv")

  val householdsXml = new File("test/input/beamville/households.xml")
  val householdsCsv = new File("test/input/beamville/households.csv")

  FileUtils.writeToFile(
    populationCsv.getAbsolutePath,
    PopulationConverter.toCsv(populationXml, populationCsv)
  )

  FileUtils.writeToFile(
    householdsCsv.getAbsolutePath,
    HouseHoldsConverter.toCsv(householdsXml, householdsCsv)
  )

}
