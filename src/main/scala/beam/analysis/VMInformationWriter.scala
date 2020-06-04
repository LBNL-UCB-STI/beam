package beam.analysis

import java.io.IOException

import beam.utils.VMInfoCollector
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.utils.io.IOUtils

class VMInformationWriter(val controllerIO: OutputDirectoryHierarchy) extends LazyLogging {
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

  def writeHeapDump(iteration: Int, suffix: String): Unit = {
    val vmInfoCollector = VMInfoCollector()
    val filePath = controllerIO.getIterationFilename(iteration, s"heapDump.$suffix.hprof")
    try {
      vmInfoCollector.dumpHeap(filePath, live = false)
    } catch {
      case e: IOException =>
        logger.error(s"Error while writing heap dump - $filePath", e)
    }
  }

  def writeVMInfo(iteration: Int, suffix: String): Unit = {
    val vmInfoCollector = VMInfoCollector()
    val filePath = controllerIO.getIterationFilename(iteration, s"vmHeapClassHistogram.$suffix.txt")

    writeToFile(vmInfoCollector.gcClassHistogram, filePath)
  }
}
