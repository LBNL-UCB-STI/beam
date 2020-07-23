package beam.sim

import java.io.{BufferedWriter, FileWriter, IOException}

import beam.analysis.physsim.{PhyssimCalcLinkSpeedDistributionStatsObject, PhyssimCalcLinkSpeedStatsObject}
import beam.analysis.plots._
import beam.utils.OutputDataDescriptor
import com.conveyal.r5.transit.TransportNetwork
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Scenario
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.controler.events.ControlerEvent

import scala.collection.JavaConverters._

/**
  * Generate data descriptions table for all output file generating classes.
  */
class BeamOutputDataDescriptionGenerator @Inject()(
  private val transportNetwork: TransportNetwork,
  private val beamServices: BeamServices,
  private val eventsManager: EventsManager,
  private val scenario: Scenario
) extends LazyLogging {

  private final val outputFileName = "dataDescriptors.csv"
  private final val outputFileHeader = "ClassName,OutputFile,Field,Description\n"
  private final val writeGraphs = beamServices.beamConfig.beam.outputs.writeGraphs

  /**
    * Generates the data descriptors and writes them to the output file.
    * @param event a controller event
    */
  def generateDescriptors(event: ControlerEvent): Unit = {
    //get all the required class file instances
    val descriptors
      : Seq[OutputDataDescription] = BeamOutputDataDescriptionGenerator.getClassesGeneratingOutputs flatMap {
      classRef =>
        classRef.getOutputDataDescriptions(event.getServices().getControlerIO()).asScala.toList
    }
    //generate csv from the data objects
    val descriptionsAsCSV = descriptors map { d =>
      d.asInstanceOf[Product].productIterator mkString ","
    } mkString "\n"
    //write the generated csv to an external file in the output folder
    val filePath = event.getServices.getControlerIO.getOutputPath + "/" + outputFileName
    writeToFile(filePath, Some(outputFileHeader), descriptionsAsCSV, None)
  }

  /**
    * Writes data to the output file at specified path.
    * @param filePath path of the output file to write data to
    * @param fileHeader an optional header to be appended (if any)
    * @param data data to be written to the file
    * @param fileFooter an optional footer to be appended (if any)
    */
  private def writeToFile(
    filePath: String,
    fileHeader: Option[String],
    data: String,
    fileFooter: Option[String]
  ): Unit = {
    val bw = new BufferedWriter(new FileWriter(filePath))
    try {
      if (fileHeader.isDefined)
        bw.append(fileHeader.get)
      bw.append(data)
      if (fileFooter.isDefined)
        bw.append(fileFooter.get)
    } catch {
      case e: IOException => logger.error("exception occurred due to ", e)
    } finally {
      bw.close()
    }
  }

}

object BeamOutputDataDescriptionGenerator {

  /**
    * creates and collects instances of all output file generating classes
    * @return collected class instances
    */
  def getClassesGeneratingOutputs: Seq[OutputDataDescriptor] = List(
    ModeChosenAnalysisObject,
    RealizedModeAnalysisObject,
    RideHailRevenueAnalysisObject,
    PersonTravelTimeAnalysisObject,
    FuelUsageAnalysisObject,
//    ExpectedMaxUtilityHeatMapObject,
    PhyssimCalcLinkSpeedStatsObject,
    PhyssimCalcLinkSpeedDistributionStatsObject,
    RideHailWaitingAnalysisObject,
    GraphSurgePricingObject,
    RideHailingWaitingSingleAnalysisObject,
    StopWatchOutputs,
    ScoreStatsOutputs,
    SummaryStatsOutputs,
    CountsCompareOutputs,
    EventOutputs,
    LegHistogramOutputs,
    RideHailTripDistanceOutputs,
    TripDurationOutputs,
    BiasErrorGraphDataOutputs,
    BiasNormalizedErrorGraphDataOutputs,
    RideHailFleetInitializer
  )

}

object ScoreStatsOutputs extends OutputDataDescriptor {

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  override def getOutputDataDescriptions(
    ioController: OutputDirectoryHierarchy
  ): java.util.List[OutputDataDescription] = {
    val outputFilePath = ioController.getOutputFilename("scorestats.txt")
    val outputDirPath = ioController.getOutputPath
    val relativePath = outputFilePath.replace(outputDirPath, "")
    val list = new java.util.ArrayList[OutputDataDescription]
    list.add(
      OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "ITERATION", "Iteration number")
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "avg. EXECUTED",
        "Average of the total execution time for the given iteration"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "avg. WORST",
        "Average of worst case time complexities for the given iteration"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "avg. AVG",
        "Average of average case time complexities for the given iteration"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "avg. BEST",
        "Average of best case time complexities for the given iteration"
      )
    )
    list
  }
}

object StopWatchOutputs extends OutputDataDescriptor {

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  override def getOutputDataDescriptions(
    ioController: OutputDirectoryHierarchy
  ): java.util.List[OutputDataDescription] = {
    val outputFilePath = ioController.getOutputFilename("stopwatch.txt")
    val outputDirPath = ioController.getOutputPath
    val relativePath = outputFilePath.replace(outputDirPath, "")
    val list = new java.util.ArrayList[OutputDataDescription]
    list.add(
      OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "Iteration", "Iteration number")
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "BEGIN iteration",
        "Begin time of the iteration"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "BEGIN iterationStartsListeners",
        "Time at which the iteration start event listeners started"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "END iterationStartsListeners",
        "Time at which  the iteration start event listeners ended"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "BEGIN replanning",
        "Time at which the replanning event started"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "END replanning",
        "Time at which the replanning event ended"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "BEGIN beforeMobsimListeners",
        "Time at which the beforeMobsim event listeners started"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "BEGIN dump all plans",
        "Begin dump all plans"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "END dump all plans",
        "End dump all plans"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "END beforeMobsimListeners",
        "Time at which the beforeMobsim event listeners ended"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "BEGIN mobsim",
        "Time at which the mobsim run started"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "END mobsim",
        "Time at which the mobsim run ended"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "BEGIN afterMobsimListeners",
        "Time at which the afterMobsim event listeners started"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "END afterMobsimListeners",
        "Time at which the afterMobsim event listeners ended"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "BEGIN scoring",
        "Time at which the scoring event started"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "END scoring",
        "Time at which the scoring event ended"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "BEGIN iterationEndsListeners",
        "Time at which the iteration ends event listeners ended"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "BEGIN compare with counts",
        "Time at which compare with counts started"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "END compare with counts",
        "Time at which compare with counts ended"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "END iteration",
        "Time at which the iteration ended"
      )
    )
    list
  }
}

object SummaryStatsOutputs extends OutputDataDescriptor {

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  override def getOutputDataDescriptions(
    ioController: OutputDirectoryHierarchy
  ): java.util.List[OutputDataDescription] = {
    val outputFilePath = ioController.getOutputFilename("summaryStats.txt")
    val outputDirPath = ioController.getOutputPath
    val relativePath = outputFilePath.replace(outputDirPath, "")
    val list = new java.util.ArrayList[OutputDataDescription]
    list.add(
      OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "Iteration", "Iteration number")
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "agentHoursOnCrowdedTransit",
        "Time taken by the agent to travel in a crowded transit"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "fuelConsumedInMJ_Diesel",
        "Amount of diesel consumed in megajoule"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "fuelConsumedInMJ_Food",
        "Amount of food consumed in megajoule"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "fuelConsumedInMJ_Electricity",
        "Amount of electricity consumed in megajoule"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "fuelConsumedInMJ_Gasoline",
        "Amount of gasoline consumed in megajoule"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "numberOfVehicles_BEV",
        "Time at which the beforeMobsim event listeners started"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "numberOfVehicles_BODY-TYPE-DEFAULT",
        "Number of vehicles of type BODY-TYPE-DEFAULT"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "numberOfVehicles_BUS-DEFAULT",
        "Number of vehicles of type BUS-DEFAULT"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "numberOfVehicles_Car",
        "Time at which the beforeMobsim event listeners ended"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "numberOfVehicles_SUBWAY-DEFAULT",
        "Time at which the mobsim run started"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "personTravelTime_car",
        "Time taken by the passenger to travel by car"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "personTravelTime_drive_transit",
        "Time taken by the passenger to drive to the transit"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "personTravelTime_others",
        "Time taken by the passenger to travel by other means"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "personTravelTime_walk",
        "Time taken by the passenger to travel on foot"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "personTravelTime_walk_transit",
        "Time taken by the passenger to walk to the transit"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "totalCostIncludingIncentive_walk_transit",
        "Total cost (including incentive) paid by the passenger to reach destination by walking to transit and then transit to destination"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "totalCostIncludingIncentive_ride_hail",
        "Total cost (including incentive) paid by the passenger to reach destination on a ride hail"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "totalIncentive_drive_transit",
        "Total incentive amount paid to passenger to reach destination by driving to transit and then transit to destination"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "totalIncentive_ride_hail",
        "Total incentive amount paid to passenger to reach destination by ride hail"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "totalIncentive_walk_transit",
        "Total incentive amount paid to passenger to reach destination by walking to transit and then transit to destination"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "totalTravelTime",
        "Total time taken by the passenger to travel from source to destination"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "totalVehicleDelay",
        "Sum of all the delay times incurred by the vehicle during the travel"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "vehicleHoursTraveled_BEV",
        "Time taken (in hours) by the vehicle to travel from source to destination"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "vehicleHoursTraveled_BODY-TYPE-DEFAULT",
        "Time taken (in hours) by the vehicle to travel from source to destination"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "vehicleHoursTraveled_BUS-DEFAULT",
        "Time taken (in hours) by the vehicle(bus) to travel from source to destination"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "vehicleHoursTraveled_Car",
        "Time taken (in hours) by the vehicle(car) to travel from source to destination"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "vehicleHoursTraveled_SUBWAY-DEFAULT",
        "Time taken (in hours) by the vehicle (subway) to travel from source to destination"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "vehicleMilesTraveled_BEV",
        "Miles covered by the vehicle to travel from source to destination"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "vehicleMilesTraveled_BODY-TYPE-DEFAULT",
        "Miles covered by the vehicle to travel from source to destination"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "vehicleMilesTraveled_BUS-DEFAULT",
        "Miles covered by the vehicle(bus) to travel from source to destination"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "vehicleMilesTraveled_Car",
        "Miles covered by the vehicle(car) to travel from source to destination"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "vehicleMilesTraveled_SUBWAY-DEFAULT",
        "Miles covered by the vehicle(subway) to travel from source to destination"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "vehicleMilesTraveled_total",
        "Miles covered by the vehicles(all modes) to travel from source to destination"
      )
    )
    list
  }
}

object CountsCompareOutputs extends OutputDataDescriptor {

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  override def getOutputDataDescriptions(
    ioController: OutputDirectoryHierarchy
  ): java.util.List[OutputDataDescription] = {
    val outputFilePath = ioController.getIterationFilename(0, "countsCompare.txt")
    val outputDirPath = ioController.getOutputPath
    val relativePath = outputFilePath.replace(outputDirPath, "")
    val list = new java.util.ArrayList[OutputDataDescription]
    list.add(
      OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "Link Id", "Iteration number")
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "Count",
        "Time taken by the agent to travel in a crowded transit"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "Station Id",
        "Amount of diesel consumed in megajoule"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "Hour",
        "Amount of food consumed in megajoule"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "MATSIM volumes",
        "Amount of electricity consumed in megajoule"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "Relative Error",
        "Amount of gasoline consumed in megajoule"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "Normalized Relative Error",
        "Time at which the beforeMobsim event listeners started"
      )
    )
    list.add(OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "GEH", "GEH"))
    list
  }
}

object EventOutputs extends OutputDataDescriptor {

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  override def getOutputDataDescriptions(
    ioController: OutputDirectoryHierarchy
  ): java.util.List[OutputDataDescription] = {
    val outputFilePath = ioController.getIterationFilename(0, "events.csv")
    val outputDirPath = ioController.getOutputPath
    val relativePath = outputFilePath.replace(outputDirPath, "")
    val list = new java.util.ArrayList[OutputDataDescription]
    list.add(
      OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "person", "Person(Agent) Id")
    )
    list.add(OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "vehicle", "vehicle id"))
    list.add(
      OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "time", "Start time of the vehicle")
    )
    list.add(OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "type", "Type of the event"))
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "fuel",
        "Type of fuel used in the vehicle"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "duration",
        "Duration of the travel"
      )
    )
    list.add(OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "cost", "Cost of travel"))
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "location.x",
        "X co-ordinate of the location"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "location.y",
        "Y co-ordinate of the location"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "parking_type",
        "Parking type chosen by the vehicle"
      )
    )
    list.add(
      OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "pricing_model", "Pricing model")
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "charging_type",
        "Charging type of the vehicle"
      )
    )
    list.add(
      OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "parking_taz", "Parking TAZ")
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "distance",
        "Distance between source and destination"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "location",
        "Location of the vehicle"
      )
    )
    list.add(OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "mode", "Mode of travel"))
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "currentTourMode",
        "Current tour mode"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "expectedMaximumUtility",
        "Expected maximum utility of the vehicle"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "availableAlternatives",
        "Available alternatives for travel for the passenger"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "personalVehicleAvailable",
        "Whether the passenger possesses a personal vehicle"
      )
    )
    list.add(OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "tourIndex", "Tour index"))
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "facility",
        "Facility availed by the passenger"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "departTime",
        "Time of departure of the vehicle"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "originX",
        "X ordinate of the passenger origin point"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "originY",
        "Y ordinate of the passenger origin point"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "destinationX",
        "X ordinate of the passenger destination point"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "destinationY",
        "Y ordinate of the passenger destination point"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "fuelType",
        "Fuel type of the vehicle"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "num_passengers",
        "Num of passengers travelling in the vehicle"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "links",
        "Number of links in the network"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "departure_time",
        "Departure time of the vehicle"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "arrival_time",
        "Arrival time of the vehicle"
      )
    )
    list.add(
      OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "vehicle_type", "Type of vehicle")
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "capacity",
        "Total capacity of the vehicle"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "start.x",
        "X ordinate of the start point"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "start.y",
        "Y ordinate of the start point"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "end.x",
        "X ordinate of the vehicle end point"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "end.y",
        "Y ordinate of the vehicle end point"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "end_leg_fuel_level",
        "Fuel level at the end of the travel"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "seating_capacity",
        "Seating capacity of the vehicle"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "costType",
        "Type of cost of travel incurred on the passenger"
      )
    )
    list
  }
}

object LegHistogramOutputs extends OutputDataDescriptor {

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  override def getOutputDataDescriptions(
    ioController: OutputDirectoryHierarchy
  ): java.util.List[OutputDataDescription] = {
    val outputFilePath = ioController.getIterationFilename(0, "legHistogram.txt")
    val outputDirPath = ioController.getOutputPath
    val relativePath = outputFilePath.replace(outputDirPath, "")
    val list = new java.util.ArrayList[OutputDataDescription]
    list.add(OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "time", "Time"))
    list.add(OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "time", "Time"))
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "departures_all",
        "Total number of departures on all modes"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "arrivals_all",
        "Total number of arrivals on all modes"
      )
    )
    list.add(
      OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "duration", "Duration of travel")
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "stuck_all",
        "Total number of travels that got stuck on all modes"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "en-route_all",
        "Total number of travels by all modes"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "departures_car",
        "Total number of departures by car"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "arrivals_car",
        "Total number of departures by car"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "stuck_car",
        "Total number of travels that got stuck while travelling by car"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "en-route_car",
        "Total number of travels made by car"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "departures_drive_transit",
        "Total number of departures by drive to transit"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "arrivals_drive_transit",
        "Total number of arrivals by drive to transit"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "stuck_drive_transit",
        "Total number of travels that got stuck while travelling by drive to transit"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "en-route_drive_transit",
        "Total number of travels made by drive to transit"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "departures_ride_hail",
        "Total number of departures by ride hail"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "arrivals_ride_hail",
        "Total number of arrivals by ride hail"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "stuck_ride_hail",
        "Total number of travels that got stuck while travelling by ride hail"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "en-route_ride_hail",
        "Total number of travels made by ride hail"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "departures_walk",
        "Total number of departures on foot"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "arrivals_walk",
        "Total number of arrivals on foot"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "stuck_walk",
        "Total number of travels that got stuck while travelling on foot"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "en-route_walk",
        "Total number of travels made on foot"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "departures_walk_transit",
        "Total number of departures by walk to transit"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "arrivals_walk_transit",
        "Total number of arrivals by walk to transit"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "stuck_walk_transit",
        "Total number of travels that got stuck while travelling by walk to transit"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "en-route_walk_transit",
        "Total number of travels made by walk to transit"
      )
    )
    list
  }
}

object RideHailTripDistanceOutputs extends OutputDataDescriptor {

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  override def getOutputDataDescriptions(
    ioController: OutputDirectoryHierarchy
  ): java.util.List[OutputDataDescription] = {
    val outputFilePath = ioController.getIterationFilename(0, "rideHailTripDistance.csv")
    val outputDirPath = ioController.getOutputPath
    val relativePath = outputFilePath.replace(outputDirPath, "")
    val list = new java.util.ArrayList[OutputDataDescription]
    list.add(OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "hour", "Hour of the day"))
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "numPassengers",
        "Number of passengers travelling in the ride hail"
      )
    )
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "vkt",
        "Total number of kilometers travelled by the ride hail vehicle"
      )
    )
    list
  }
}

object TripDurationOutputs extends OutputDataDescriptor {

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  override def getOutputDataDescriptions(
    ioController: OutputDirectoryHierarchy
  ): java.util.List[OutputDataDescription] = {
    val outputFilePath = ioController.getIterationFilename(0, "tripDuration.txt")
    val outputDirPath = ioController.getOutputPath
    val relativePath = outputFilePath.replace(outputDirPath, "")
    val list = new java.util.ArrayList[OutputDataDescription]
    list.add(OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "pattern", "Pattern"))
    list.add(OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "(5*i)+", "Value"))
    list
  }
}

object BiasErrorGraphDataOutputs extends OutputDataDescriptor {

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  override def getOutputDataDescriptions(
    ioController: OutputDirectoryHierarchy
  ): java.util.List[OutputDataDescription] = {
    val outputFilePath = ioController.getIterationFilename(0, "biasErrorGraphData.txt")
    val outputDirPath = ioController.getOutputPath
    val relativePath = outputFilePath.replace(outputDirPath, "")
    val list = new java.util.ArrayList[OutputDataDescription]
    list.add(OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "hour", "Hour of the day"))
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "mean relative error",
        "Mean relative error"
      )
    )
    list.add(
      OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "mean bias", "Mean bias value")
    )
    list
  }
}

object BiasNormalizedErrorGraphDataOutputs extends OutputDataDescriptor {

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  override def getOutputDataDescriptions(
    ioController: OutputDirectoryHierarchy
  ): java.util.List[OutputDataDescription] = {
    val outputFilePath = ioController.getIterationFilename(0, "biasNormalizedErrorGraphData.txt")
    val outputDirPath = ioController.getOutputPath
    val relativePath = outputFilePath.replace(outputDirPath, "")
    val list = new java.util.ArrayList[OutputDataDescription]
    list.add(OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "hour", "Hour of the day"))
    list.add(
      OutputDataDescription(
        this.getClass.getSimpleName.dropRight(1),
        relativePath,
        "mean normalized relative error",
        "Mean normalized relative error"
      )
    )
    list.add(
      OutputDataDescription(this.getClass.getSimpleName.dropRight(1), relativePath, "mean bias", "Mean bias value")
    )
    list
  }
}
