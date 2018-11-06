package beam.sim

import java.io.{BufferedWriter, FileWriter, IOException}

import beam.agentsim.agents.ridehail.RideHailSurgePricingManager
import beam.analysis.plots.{ModeChosenAnalysis, RealizedModeAnalysis, RideHailRevenueAnalysis}
import beam.utils.OutputDataDescriptor
import org.matsim.core.controler.events.ControlerEvent

import scala.collection.JavaConverters._

/**
  * Generate data descriptions table for all output file generating classes.
  */
object BeamOutputDataDescriptionGenerator {
  private final val outputFileName = "dataDescriptors.csv"
  private final val outputFileHeader = "ClassName,OutputFile,Field,Description\n"

  /**
    * Generates the data descriptors and writes them to the output file.
    * @param event a controller event
    * @param beamServices beam service class
    */
  def generateDescriptors(event : ControlerEvent,beamServices: BeamServices): Unit = {
    //get all the required class file instances
    val descriptors: Seq[OutputDataDescription] = getClassesGeneratingOutputs(beamServices) flatMap { classRef =>
      classRef.getOutputDataDescriptions.asScala.toList
    }
    //generate csv from the data objects
    val descriptionsAsCSV = descriptors map { d =>
      d.asInstanceOf[Product].productIterator mkString ","
    } mkString "\n"
    //write the generated csv to an external file in the output folder
    val filePath = event.getServices.getControlerIO.getOutputPath + "/" + outputFileName
    writeToFile(filePath,Some(outputFileHeader),descriptionsAsCSV,None)
  }

  /**
    * creates and collects instances of all output file generating classes
    * @param beamServices beam service class
    * @return collected class instances
    */
  private def getClassesGeneratingOutputs(beamServices: BeamServices): List[OutputDataDescriptor] = List(
    new ModeChosenAnalysis(new ModeChosenAnalysis.ModeChosenComputation, beamServices.beamConfig),
    new RealizedModeAnalysis(new RealizedModeAnalysis.RealizedModesStatsComputation),
    new RideHailRevenueAnalysis(new RideHailSurgePricingManager(beamServices))
  )

  /**
    * Writes data to the output file at specified path.
    * @param filePath path of the output file to write data to
    * @param fileHeader an optional header to be appended (if any)
    * @param data data to be written to the file
    * @param fileFooter an optional footer to be appended (if any)
    */
  private def writeToFile(filePath : String,fileHeader : Option[String],data : String,fileFooter : Option[String]): Unit = {
    val bw = new BufferedWriter(new FileWriter(filePath))
    try {
      if(fileHeader.isDefined)
        bw.append(fileHeader.get)
      bw.append(data)
      if(fileFooter.isDefined)
        bw.append(fileFooter.get)
    }
    catch {
      case e: IOException =>
        e.printStackTrace()
    } finally {
      bw.close()
    }
  }

}
