package beam.sim

import java.io.{BufferedWriter, FileWriter, IOException}

import akka.actor.ActorSystem
import beam.agentsim.agents.ridehail.RideHailSurgePricingManager
import beam.analysis.physsim.{PhyssimCalcLinkSpeedDistributionStats, PhyssimCalcLinkSpeedStats}
import beam.analysis.plots._
import beam.analysis.via.ExpectedMaxUtilityHeatMap
import beam.utils.OutputDataDescriptor
import com.conveyal.r5.transit.TransportNetwork
import com.google.inject.Inject
import org.matsim.api.core.v01.Scenario
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.events.ControlerEvent

import scala.collection.JavaConverters._

/**
  * Generate data descriptions table for all output file generating classes.
  */
class BeamOutputDataDescriptionGenerator @Inject()
(private val actorSystem: ActorSystem,
 private val transportNetwork: TransportNetwork,
 private val beamServices: BeamServices,
 private val eventsManager: EventsManager,
 private val scenario: Scenario) {

  private final val outputFileName = "dataDescriptors.csv"
  private final val outputFileHeader = "ClassName,OutputFile,Field,Description\n"

  /**
    * Generates the data descriptors and writes them to the output file.
    * @param event a controller event
    */
  def generateDescriptors(event : ControlerEvent): Unit = {
    //get all the required class file instances
    val descriptors: Seq[OutputDataDescription] = getClassesGeneratingOutputs(event) flatMap { classRef =>
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
    * @return collected class instances
    */
  private def getClassesGeneratingOutputs(event : ControlerEvent): List[OutputDataDescriptor] = List(
    new ModeChosenAnalysis(new ModeChosenAnalysis.ModeChosenComputation, this.beamServices.beamConfig),
    new RealizedModeAnalysis(new RealizedModeAnalysis.RealizedModesStatsComputation),
    new RideHailRevenueAnalysis(new RideHailSurgePricingManager(this.beamServices)),
    new PersonTravelTimeAnalysis(new PersonTravelTimeAnalysis.PersonTravelTimeComputation),
    new FuelUsageAnalysis(new FuelUsageAnalysis.FuelUsageStatsComputation),
    new ExpectedMaxUtilityHeatMap(
      this.eventsManager,
      this.scenario.getNetwork,
      event.getServices.getControlerIO,
      this.beamServices.beamConfig.beam.outputs.writeEventsInterval
    ),
    new PhyssimCalcLinkSpeedStats(scenario.getNetwork, event.getServices.getControlerIO, beamServices.beamConfig),
    new PhyssimCalcLinkSpeedDistributionStats(scenario.getNetwork, event.getServices.getControlerIO, beamServices.beamConfig),
    new RideHailWaitingAnalysis(new RideHailWaitingAnalysis.WaitingStatsComputation, beamServices.beamConfig),
    new GraphSurgePricing(new RideHailSurgePricingManager(beamServices)),
    new RideHailingWaitingSingleAnalysis(beamServices.beamConfig, new RideHailingWaitingSingleAnalysis.RideHailingWaitingSingleComputation)
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
