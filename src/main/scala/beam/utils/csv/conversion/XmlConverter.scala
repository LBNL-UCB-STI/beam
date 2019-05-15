package beam.utils.csv.conversion

import java.io.{File, FileInputStream, FileOutputStream}
import java.nio.file.Files
import java.util.zip.GZIPInputStream

import beam.sim.config.BeamConfig
import beam.utils.FileUtils

object XmlConverter extends App {
  val path = "test/input/beamville"

  /*
/src/beam/output/beamville/beamville__2019-05-07_23-09-15/outputCounts.xml.gz
/src/beam/output/beamville/beamville__2019-05-07_23-09-15/outputFacilities.xml.gz
/src/beam/output/beamville/beamville__2019-05-07_23-09-15/outputLanes.xml.gz
/src/beam/output/beamville/beamville__2019-05-07_23-09-15/outputHouseholds.xml.gz
/src/beam/output/beamville/beamville__2019-05-07_23-09-15/outputNetwork.xml.gz
/src/beam/output/beamville/beamville__2019-05-07_23-09-15/outputConfig.xml
/src/beam/output/beamville/beamville__2019-05-07_23-09-15/outputVehicles.xml.gz
/src/beam/output/beamville/beamville__2019-05-07_23-09-15/outputPlans.xml.gz
/src/beam/output/beamville/beamville__2019-05-07_23-09-15/outputPersonAttributes.xml.gz
   */

//  private val populationXml = new File("test/input/beamville/population.xml")
//  private val householdsXml = new File("test/input/beamville/households.xml")
//  private val houseHoldAttributesXml = new File("test/input/beamville/householdAttributes.xml")
//  private val populationAttributesXml = new File("test/input/beamville/populationAttributesWithExclusions.xml")
//
//  convert(populationXml, new PopulationXml2CsvConverter(householdsXml, populationAttributesXml).toCsv)
//  convert(householdsXml, new HouseholdsXml2CsvConverter(houseHoldAttributesXml).toCsv)
//  convert(populationXml, PlansXml2CsvConverter.toCsv, newName = Some(new File("test/input/beamville/plans")))

  def generatePopulationCsv(beamConfig: BeamConfig, allFiles: Seq[File]): File = {
    val populationXml = new File(beamConfig.beam.agentsim.agents.plans.inputPlansFilePath)
    val householdsXml: File = extractFile(
      allFiles
        .find(_.getAbsolutePath.endsWith("Households.xml.gz"))
        .getOrElse(throw new IllegalStateException("could not find Households.xml.gz"))
    )
    val populationAttributesXml: File = extractFile(
      allFiles
        .find(_.getAbsolutePath.endsWith("PersonAttributes.xml.gz"))
        .getOrElse(throw new IllegalStateException("could not find PersonAttributes.xml.gz"))
    )
    convert(
      populationXml,
      new PopulationXml2CsvConverter(householdsXml, populationAttributesXml).toCsv,
      Some(new File(allFiles.head.getParentFile + "/population"))
    )
  }

  def generateHouseholdsCsv(beamConfig: BeamConfig, allFiles: Seq[File]): File = {
    val householdsXml = extractFile(
      allFiles
        .find(_.getAbsolutePath.endsWith("Households.xml.gz"))
        .getOrElse(throw new IllegalStateException("could not find PersonAttributes.xml.gz"))
    )
    val houseHoldAttributesXml = new File(beamConfig.beam.agentsim.agents.households.inputHouseholdAttributesFilePath)
    convert(
      householdsXml,
      new HouseholdsXml2CsvConverter(houseHoldAttributesXml).toCsv,
      Some(new File(allFiles.head.getParentFile + "/households"))
    )
  }

  def generatePlansCsv(beamConfig: BeamConfig, allFiles: Seq[File]): File = {
    val populationXml = new File(beamConfig.beam.agentsim.agents.plans.inputPlansFilePath)
    convert(
      populationXml,
      PlansXml2CsvConverter.toCsv,
      Some(new File(allFiles.head.getParentFile + "/plans"))
    )
  }

  def generateVehiclesCsv(beamConfig: BeamConfig, allFiles: Seq[File]): File = {
    val file = new File(beamConfig.beam.agentsim.agents.vehicles.vehiclesFilePath)
    val stream = new FileInputStream(file)
    val newFile = new File(allFiles.headOption.get.getParentFile + "/vehicles.csv")
    println(s"Generating file $newFile")
    Files.copy(stream, newFile.toPath)
    stream.close()
    newFile
  }

  def csvFileName(file: File): File = new File(file.getAbsolutePath.stripSuffix(".xml") + ".csv")

  def convert(file: File, f: File => Iterator[String], newName: Option[File] = None): File = {
    val csvFile = newName.orElse(Some(file)).map(csvFileName).get
    println(s"Generating file $csvFile")
    FileUtils.writeToFile(
      csvFile.getAbsolutePath,
      f(file)
    )
    csvFile
  }

  def extractFile(gzipFile: File): File = {
    val result = File.createTempFile("somePrefix", ".xml")
    FileUtils.using(new FileOutputStream(result)) { outputStream =>
      val in = new GZIPInputStream(new FileInputStream(gzipFile))
      val buf = new Array[Byte](1024)
      var len = 0
      while ({
        len = in.read(buf)
        len > 0
      }) {
        outputStream.write(buf, 0, len)
      }
    }
    result
  }

}
