package beam.analysis

import java.io.IOException

import beam.utils.csv.CsvWriter
import beam.utils.{VMClassInfo, VMInfoCollector}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.controler.events.{IterationEndsEvent, IterationStartsEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, IterationStartsListener}
import scala.collection.mutable
import scala.util.control.NonFatal

class VMInformationCollector(val controllerIO: OutputDirectoryHierarchy, val takeTopClasses: Int = 10)
    extends LazyLogging
    with IterationEndsListener
    with IterationStartsListener {

  val bytesInMegabyte: Double = 1024.0 * 1024.0

  val classNameToBytesPerIteration: mutable.Map[String, mutable.ListBuffer[Long]] =
    new mutable.HashMap[String, mutable.ListBuffer[Long]]()

  def writeHeapClassesInformation(
    classToIterationValues: Vector[(String, mutable.ListBuffer[Long])],
    filePath: String
  ): Unit = {

    val header = "iteration" +: classToIterationValues.map(_._1)
    val numberOfIterations = classToIterationValues(0)._2.length
    val csvWriter = new CsvWriter(filePath, header)

    try {
      for (iteration <- 0 until numberOfIterations) {
        val row = iteration +: classToIterationValues.map(_._2(iteration) / bytesInMegabyte)
        csvWriter.write(row: _*)
      }
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Could not write class heap information to '$filePath': ${ex.getMessage}", ex)
    } finally {
      csvWriter.close()
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

  def analyzeVMClassHystogram(classes: Seq[VMClassInfo], iteration: Int): Unit = {
    def fillMap(
      classNameToValue: mutable.Map[String, mutable.ListBuffer[Long]],
      className: String,
      value: Long
    ): Unit = {
      classNameToValue.get(className) match {
        case Some(iterationValues) =>
          while (iterationValues.length < iteration) {
            iterationValues += 0L
          }
          iterationValues += value
        case None =>
          val iterationValues = if (iteration > 0) {
            mutable.ListBuffer.fill[Long](iteration)(0L)
          } else {
            mutable.ListBuffer.empty[Long]
          }
          iterationValues += value
          classNameToValue(className) = iterationValues
      }
    }

    def fixMap(classNameToValue: mutable.Map[String, mutable.ListBuffer[Long]]): Unit = {
      classNameToValue.values.foreach { iterationValues =>
        while (iterationValues.size < iteration + 1) {
          iterationValues += 0L
        }
      }
    }

    classes.foreach { classInfo =>
      fillMap(classNameToBytesPerIteration, classInfo.className, classInfo.numberOfBytes)
    }

    fixMap(classNameToBytesPerIteration)
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    val vmInfoCollector = VMInfoCollector()
    val classes = vmInfoCollector.gcClassHistogram(takeTopClasses)

    analyzeVMClassHystogram(classes, event.getIteration)

    val filePath = controllerIO.getOutputFilename("vmNumberOfMBytesOfClassOnHeap.csv.gz")
    writeHeapClassesInformation(classNameToBytesPerIteration.toVector, filePath)
  }

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {}
}
