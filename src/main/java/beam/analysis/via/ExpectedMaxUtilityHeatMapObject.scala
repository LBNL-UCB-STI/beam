package beam.analysis.via

import java.util.Collections
import java.util

import beam.sim.OutputDataDescription
import beam.utils.OutputDataDescriptor
import org.matsim.core.controler.OutputDirectoryHierarchy

object ExpectedMaxUtilityHeatMapObject extends OutputDataDescriptor {

//  private val fileBaseName = ExpectedMaxUtilityHeatMap.fileBaseName

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  def getOutputDataDescriptions(ioController: OutputDirectoryHierarchy): util.List[OutputDataDescription] = {
    Collections.emptyList()
    // TODO: what should be the value of controlerIO?
//    val outputFilePath: String = controlerIO.getIterationFilename(0, fileBaseName + ".csv")
//    val outputDirPath: String = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputPath
//    val relativePath: String = outputFilePath.replace(outputDirPath, "")
//    val list: util.List[OutputDataDescription] = new util.ArrayList[OutputDataDescription]
//    list
//      .add(OutputDataDescription(this.getClass.getSimpleName, relativePath, "time", "Time of the event occurrence"))
//    list
//      .add(
//        OutputDataDescription(
//          getClass
//            .getSimpleName, relativePath, "x", "X co-ordinate of the network link location"
//        )
//      )
//    list
//      .add(
//        OutputDataDescription(
//          getClass
//            .getSimpleName, relativePath, "y", "Y co-ordinate of the network link location"
//        )
//      )
//    list
//      .add(
//        OutputDataDescription(
//          getClass
//            .getSimpleName, relativePath, "expectedMaximumUtility", "Expected maximum utility of the network link for the event"
//        )
//      )
//    list
  }

}
