package scripts

import java.io.File

import beam.utils.FileUtils

object XmlConverter extends App {
  val path = "test/input/beamville"

  private val populationXml = new File("test/input/beamville/population.xml")
  private val householdsXml = new File("test/input/beamville/households.xml")
  private val houseHoldAttributesXml = new File("test/input/beamville/householdAttributes.xml")
  private val populationAttributesXml = new File("test/input/beamville/populationAttributesWithExclusions.xml")

  convert(populationXml, new PopulationXml2CsvConverter(householdsXml, populationAttributesXml).toCsv)
  convert(householdsXml, new HouseholdsXml2CsvConverter(houseHoldAttributesXml).toCsv)
  convert(populationXml, PlansXml2CsvConverter.toCsv, newName = Some(new File("test/input/beamville/plans")))

  def csvFileName(file: File): File = new File(file.getAbsolutePath.stripSuffix(".xml") + ".csv")

  def convert(file: File, f: File => Iterator[String], newName: Option[File] = None): Unit = {
    val csvFile = newName.orElse(Some(file)).map(csvFileName).get
    println(s"Generating file $csvFile")
    FileUtils.writeToFile(
      csvFile.getAbsolutePath,
      f(file)
    )
  }

}
