package scripts
import java.io.File

import beam.utils.FileUtils

object XmlConverter extends App {
  val populationXml = new File("test/input/beamville/population.xml")
  val populationCsv = new File("test/input/beamville/population.csv")

  FileUtils.writeToFile(
    populationCsv.getAbsolutePath,
    PopulationConverter.toCsv(populationXml, populationCsv)
  )

}
