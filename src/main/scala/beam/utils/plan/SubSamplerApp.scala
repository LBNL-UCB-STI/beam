package beam.utils.plan


import java.io.{BufferedInputStream, File, FileInputStream}
import java.nio.file.Paths
import java.util.Locale
import java.util.zip.GZIPInputStream

import org.matsim.core.config.ConfigUtils
import org.matsim.core.network.io.MatsimNetworkReader
import org.matsim.core.population.io.{StreamingPopulationReader, StreamingPopulationWriter}
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.matsim.core.utils.io.UnicodeInputStream

import scala.util.Try


object SubSamplerApp extends App {

  def createSample(inputPopulationFile: File, networkFile: File, samplesize: Double, outputPopulationFile: File): Unit = {
    val sc = ScenarioUtils.createMutableScenario(ConfigUtils.createConfig)


      if (networkFile != null) {
        var fis: FileInputStream = null
        var is: BufferedInputStream = null

        try {
          fis = new FileInputStream(networkFile)
          is = if (networkFile.getName.toLowerCase(Locale.ROOT).endsWith(".gz")) new BufferedInputStream(new UnicodeInputStream(new GZIPInputStream(fis)))
          else new BufferedInputStream(new UnicodeInputStream(fis))
          println("Loading Network…")
          new MatsimNetworkReader(sc.getNetwork).parse(is)
        } finally {
          if (fis != null) fis.close()
          if (is != null) is.close()
        }
      }

    //		Population pop = (Population) sc.getPopulation();
    val reader = new StreamingPopulationReader(sc)
//    StreamingDeprecated.setIsStreaming(reader, true)
    var writer: StreamingPopulationWriter = null
    try {
      val utm2Wgs: GeotoolsTransformation =
      new GeotoolsTransformation("epsg:4326", "epsg:26914")
      writer = new StreamingPopulationWriter(utm2Wgs, samplesize)
      writer.startStreaming(outputPopulationFile.getAbsolutePath)
      val algo = writer
      reader.addAlgorithm(algo)

        val fis = new FileInputStream(inputPopulationFile)
        val is = if (inputPopulationFile.getName.toLowerCase(Locale.ROOT).endsWith(".gz")) new BufferedInputStream(new UnicodeInputStream(new GZIPInputStream(fis)))
        else new BufferedInputStream(new UnicodeInputStream(fis))
        try {
          println("Creating Population Sample…")
          reader.parse(is)
        } finally {
          if (fis != null) fis.close()
          if (is != null) is.close()
        }

    } catch {
      case e: NullPointerException =>
        throw new RuntimeException(e)
    } finally if (writer != null) writer.closeStreaming()
  }

/*
C:/Users/zeesh/project/BeamCompetitions/fixed-data/sioux_faux/sample/15k/population.xml.gz
C:/Users/zeesh/project/BeamCompetitions/fixed-data/sioux_faux/r5/physsim-network.xml
10 C:/Users/zeesh/project/BeamCompetitions/fixed-data/sioux_faux/sample/1.5k/population.xml.gz

test/input/sf-light/sample/25k/population.xml.gz
test/input/sf-light/r5/physsim-network.xml
10 test/input/sf-light/sample/1.5k/population.xml.gz
 */

  val sampler = SubSamplerApp

  sampler.createSample(Paths.get(args(0)).toFile, Paths.get(args(1)).toFile, Try(args(2).toDouble).getOrElse(50), Paths.get(args(3)).toFile)
}
