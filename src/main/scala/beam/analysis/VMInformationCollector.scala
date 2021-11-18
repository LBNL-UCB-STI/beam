package beam.analysis

import java.io.IOException

import beam.analysis.plots.{GraphUtils, GraphsStatsAgentSimEventsListener}
import beam.utils.csv.CsvWriter
import beam.utils.{VMClassInfo, VMUtils}
import com.typesafe.scalalogging.LazyLogging
import org.jfree.chart.ChartFactory
import org.jfree.chart.plot.PlotOrientation
import org.jfree.data.category.{CategoryDataset, DefaultCategoryDataset}
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.controler.events.{IterationEndsEvent, IterationStartsEvent}
import org.matsim.core.controler.listener.IterationEndsListener

import scala.collection.mutable
import scala.util.control.NonFatal

class VMInformationCollector(val controllerIO: OutputDirectoryHierarchy, val takeTopClasses: Int = 10)
    extends LazyLogging
    with IterationEndsListener {

  val bytesInGb: Double = 1024.0 * 1024.0 * 1024.0
  val baseNumberOfMBytesOfClassOnHeapFileName = "vmNumberOfMBytesOfClassOnHeap"

  val classNameToBytesPerIteration: mutable.Map[String, mutable.ListBuffer[Long]] =
    new mutable.HashMap[String, mutable.ListBuffer[Long]]()

  def writeHeapClassesInformation(
    classToIterationValues: Vector[(String, Seq[Double])],
    filePath: String
  ): Unit = {

    val header = "iteration\\className" +: classToIterationValues.map(_._1)
    val numberOfIterations = classToIterationValues(0)._2.length
    val csvWriter = new CsvWriter(filePath, header)

    try {
      for (iteration <- 0 until numberOfIterations) {
        val row = iteration +: classToIterationValues.map(_._2(iteration))
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
    val vmInfoCollector = VMUtils()
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

  private def createTypeSizeOnHeapDataset(
    classToIterationValues: Vector[(String, Seq[Double])]
  ): CategoryDataset = {
    val dataset = new DefaultCategoryDataset

    classToIterationValues.foreach { case (className, sizePerIteration) =>
      sizePerIteration.zipWithIndex.foreach { case (sizeOnHeap, iteration) =>
        dataset.addValue(sizeOnHeap, className, iteration)
      }
    }

    dataset
  }

  private def createTypeSizeOnHeapGraph(
    outputFileName: String,
    dataset: CategoryDataset
  ): Unit = {
    val chart = ChartFactory.createBarChart(
      "Size of all type instances on a heap",
      "Iteration",
      "GB",
      dataset,
      PlotOrientation.VERTICAL,
      true,
      true,
      false
    )

    GraphUtils.saveJFreeChartAsPNG(
      chart,
      outputFileName,
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    val vmInfoCollector = VMUtils()
    val classes = vmInfoCollector.gcClassHistogram(takeTopClasses)

    analyzeVMClassHystogram(classes, event.getIteration)
    val typeSizeOnHeap = classNameToBytesPerIteration.map { case (className, values) =>
      (className, values.map(_ / bytesInGb))
    }.toVector

    val csvFilePath = controllerIO.getOutputFilename(s"$baseNumberOfMBytesOfClassOnHeapFileName.csv.gz")
    writeHeapClassesInformation(typeSizeOnHeap, csvFilePath)

    val pngFilePath = controllerIO.getOutputFilename(s"$baseNumberOfMBytesOfClassOnHeapFileName.png")
    val typeSizeOnHeapDataSet = createTypeSizeOnHeapDataset(typeSizeOnHeap)
    createTypeSizeOnHeapGraph(pngFilePath, typeSizeOnHeapDataSet)
  }
}
