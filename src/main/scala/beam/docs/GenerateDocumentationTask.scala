package beam.docs

import java.io.PrintWriter
import java.nio.file.{FileSystems, Path, Paths}

import scala.collection.JavaConverters._

import beam.analysis.plots.GraphsStatsAgentSimEventsListener
import beam.sim.{BeamOutputDataDescriptionGenerator, OutputDataDescription, ScoreStatsOutputs}
import beam.utils.OutputDataDescriptor
import com.typesafe.scalalogging.StrictLogging
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting

object GenerateDocumentationTask extends App with StrictLogging {

  runApp()

  def runApp(): Unit = {
    logger.info("Generating Output data description...")
    val outputDirectory = FileSystems.getDefault.getPath(".", "docs")
      .toAbsolutePath
      .normalize()

    initializeDependencies(outputDirectory.toString)

    BeamOutputDataDescriptionGenerator.getClassesGeneratingOutputs.foreach { clazz: OutputDataDescriptor =>
      val outputFilename = clazz.getClass.getSimpleName
      val content = buildDocument(clazz)
      val outputFile = Paths.get(outputDirectory.toString, outputFilename + ".rst")
      writeFile(content, outputFile)
      logger.info("Generating Output data description finished")
    }

  }

  def loadValues(): Seq[OutputDataDescription] = {
    ScoreStatsOutputs.getOutputDataDescriptions.asScala
  }

  private def initializeDependencies(outputDirectory: String): Unit = {
    GraphsStatsAgentSimEventsListener.CONTROLLER_IO = new OutputDirectoryHierarchy(
      outputDirectory,
      OverwriteFileSetting.overwriteExistingFiles
    )
  }

  def buildDocument(descriptor: OutputDataDescriptor ): String = {
    new StringBuilder(buildTitle(descriptor))
      .append(buildTable(descriptor))
      .toString()
  }

  def buildTitle(descriptor: OutputDataDescriptor): String = {
    s"""
      |${descriptor.getClass.getName}
      |=======================
      |""".stripMargin
  }

  def buildTable(descriptor: OutputDataDescriptor): String = {
    val eol = System.lineSeparator()

    val allValues: Seq[OutputDataDescription] = descriptor.getOutputDataDescriptions.asScala

    val columns: Seq[String] = ReflectionUtil.classAccessors[OutputDataDescription].map(_.name.toString)
    val columnsSize: Map[String, Int] = columns.map { fieldName =>
      fieldName -> allValues.map(record => ReflectionUtil.getValue(record, fieldName).toString.length).max
    }.toMap

    val lineBorder = columns.map { col =>
      "".padTo(columnsSize(col), "-").mkString
    }.mkString("+-", "-+-", "-+")

    val headerTitle = columns.map { col =>
      col.padTo(columnsSize(col), " ").mkString
    }.mkString("| ", " | ", " |")

    val headerBottomBorder = columns.map { col =>
      "".padTo(columnsSize(col), "=").mkString
    }.mkString("+=", "=+=", "=+")

    val body = allValues.map { record =>
      rowAsString(record, columns, columnsSize) + eol + lineBorder
    }.mkString(eol)

    new StringBuilder(lineBorder)
      .append(eol)
      .append(headerTitle)
      .append(eol)
      .append(headerBottomBorder)
      .append(eol)
      .append(body)
      .append(eol)
      .toString
  }

  def rowAsString(obj: OutputDataDescription, columns: Seq[String], sizes: Map[String, Int]): String = {
    columns.map { column =>
      val fieldValue = ReflectionUtil.getValue(obj, column).toString
      fieldValue.padTo(sizes(column), " ").mkString
    }.mkString("| ", " | ", " |")
  }

  def writeFile(fullTable: String, path: Path): Unit = {
    val pw = new PrintWriter(path.toFile)
    try {
      pw.write(fullTable)
    } finally {
      pw.close()
    }
  }

}
