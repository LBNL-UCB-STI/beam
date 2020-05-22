package beam.analysis

import java.io.IOException

import beam.utils.VMInfoCollector
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.controler.events.{IterationEndsEvent, IterationStartsEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, IterationStartsListener}
import org.matsim.core.utils.io.IOUtils

class VMInformationWriter extends LazyLogging with IterationEndsListener with IterationStartsListener {

  private def writeToFile(content: String, filePath: String): Unit = {
    val bw = IOUtils.getBufferedWriter(filePath)
    try {
      bw.write(content)
    } catch {
      case e: IOException =>
        logger.error(s"Error while writing data to file - $filePath", e)
    } finally {
      bw.close()
    }
  }

  private def writeClassHistogram(
    vmInfoCollector: VMInfoCollector,
    controllerIO: OutputDirectoryHierarchy,
    iteration: Int,
    suffix: String
  ): Unit = {
    val filePath = controllerIO.getIterationFilename(iteration, s"vmHeapClassHistogram$suffix.txt")
    writeToFile(vmInfoCollector.gcClassHistogram, filePath)
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    val vmInfoCollector = VMInfoCollector()
    val controllerIO = event.getServices.getControlerIO
    val iteration = event.getIteration

    writeClassHistogram(vmInfoCollector, controllerIO, iteration, "AtIterationEnd")
  }

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
    val vmInfoCollector = VMInfoCollector()
    val controllerIO = event.getServices.getControlerIO
    val iteration = event.getIteration

    writeClassHistogram(vmInfoCollector, controllerIO, iteration, "AtIterationStart")
  }
}
