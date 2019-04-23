package scripts
import java.io.File

import beam.utils.FileUtils

object XmlConverter extends App {
  val path = "test/input/beamville"

  private val populationXml = new File("test/input/beamville/population.xml")
  private val householdsXml = new File("test/input/beamville/households.xml")
  private val houseHoldAttributesXml = new File("/src/beam/test/input/beamville/householdAttributes.xml")
  private val populationAttributesXml = new File(
    "/src/beam/test/input/beamville/populationAttributesWithExclusions.xml"
  )

//  convert(populationXml, PopulationConverter.toCsv)
//  convert(householdsXml, HouseHoldsConverter.toCsv)
//  convert(houseHoldAttributesXml, HouseHoldAttributesConverter.toCsv)
  convert(populationAttributesXml, PopulationAttributesConverter.toCsv)

  def csvFileName(file: File): File = new File(file.getAbsolutePath.stripSuffix(".xml") + ".csv")

  def convert(file: File, f: File => Iterator[String]): Unit = {
    val csvFile = csvFileName(file)
    println(s"Generating file $csvFile")
    FileUtils.writeToFile(
      csvFile.getAbsolutePath,
      f(file)
    )
  }

}
