package beam.docs

import java.io.{FileOutputStream, PrintWriter}
import java.nio.file.{FileSystems, Path, Paths}

import scala.collection.JavaConverters._

import beam.sim.{BeamOutputDataDescriptionGenerator, OutputDataDescription}
import beam.utils.OutputDataDescriptor
import com.typesafe.scalalogging.StrictLogging
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting

object GenerateDocumentationTask extends App with StrictLogging {
  val eol = System.lineSeparator()

  runApp()

  def runApp(): Unit = {
    logger.info("Generating Output data description started...")
    val outputDirectory = FileSystems.getDefault
      .getPath(".", "docs")
      .toAbsolutePath
      .normalize()

    val ioController = initializeDependencies(outputDirectory.toString)

    val outputFile = Paths.get(outputDirectory.toString, "outputs.rst")

    addMainTitle(outputFile)

    BeamOutputDataDescriptionGenerator.getClassesGeneratingOutputs.foreach { clazz: OutputDataDescriptor =>
      logger.info(s"Writing ${clazz.getClass.getName.dropRight(1)}...")
      val classContent = buildDocument(clazz, ioController)
      appendToFile(classContent, outputFile)
    }
    logger.info("Generating Output data description finished.")
  }

  private def addMainTitle(outputFile: Path): Unit = {
    val documentHeader = s".. _model-outputs:$eol$eol"
    val documentTile = s"Model Outputs$eol=============$eol"
    new PrintWriter(outputFile.toFile)
      .append(documentHeader)
      .append(documentTile)
      .close()
  }

  private def initializeDependencies(outputDirectory: String): OutputDirectoryHierarchy = {
    new OutputDirectoryHierarchy(
      outputDirectory,
      OverwriteFileSetting.overwriteExistingFiles
    )
  }

  def buildDocument(descriptor: OutputDataDescriptor, ioController: OutputDirectoryHierarchy): String = {
    val allValues: Seq[OutputDataDescription] = descriptor.getOutputDataDescriptions(ioController).asScala

    val columns: Seq[String] = Seq("field", "description")
    val columnsSize: Map[String, Int] = calculateColumnSize(allValues, columns)

    val groups = allValues.map(_.outputFile).distinct
    groups
      .map { groupFile =>
        val groupValues = allValues
          .filter(_.outputFile == groupFile)
          .map(descriptor => descriptor.copy(className = descriptor.className.dropRight(1)))
        buildGroup(groupFile, groupValues, columns, columnsSize)
      }
      .mkString(eol)
  }

  private def calculateColumnSize(allValues: Seq[OutputDataDescription], columns: Seq[String]): Map[String, Int] = {
    columns.map { fieldName =>
      fieldName -> {
        val maxContentSize = allValues.map(record => ReflectionUtil.getValue(record, fieldName).toString.length).max
        val columnTitleSize = fieldName.length
        Math.max(maxContentSize, columnTitleSize)
      }
    }.toMap
  }

  def formatTitle(title: String): String = {
    val prefix = "File: "
    s"""
       |$prefix$title
       |${"-" * (prefix.length + title.length)}
       |""".stripMargin
  }

  private def buildGroup(
    title: String,
    allValues: Seq[OutputDataDescription],
    columns: Seq[String],
    columnsSize: Map[String, Int]
  ): String = {
    val theClassname = allValues.head.className
    new StringBuilder(formatTitle(title))
      .append(buildHeader(columns, columnsSize, theClassname))
      .append(buildTableBody(allValues, columns, columnsSize))
      .append(eol)
      .toString
  }

  private def buildTableBody(
    allValues: Seq[OutputDataDescription],
    columns: Seq[String],
    columnsSize: Map[String, Int]
  ): String = {
    allValues
      .map { record =>
        rowAsString(record, columns, columnsSize)
      }
      .mkString(eol)
  }

  private def buildHeader(columns: Seq[String], columnsSize: Map[String, Int], theClassname: String): String = {
    val headerClassName = s"Classname: $theClassname $eol"
    val headerTopBorder = columns
      .map { col =>
        "".padTo(columnsSize(col), "-").mkString
      }
      .mkString("+-", "-+-", "-+")

    val headerTitle = columns
      .map { col =>
        col.padTo(columnsSize(col), " ").mkString
      }
      .mkString("| ", " | ", " |")

    val headerBottomBorder = columns
      .map { col =>
        "".padTo(columnsSize(col), "=").mkString
      }
      .mkString("+=", "=+=", "=+")

    eol + headerClassName + eol + headerTopBorder + eol + headerTitle + eol + headerBottomBorder + eol
  }

  def rowAsString(obj: OutputDataDescription, columns: Seq[String], sizes: Map[String, Int]): String = {
    val bottomBorder = columns
      .map { col =>
        "".padTo(sizes(col), "-").mkString
      }
      .mkString("+-", "-+-", "-+")

    columns
      .map { column =>
        val fieldValue = ReflectionUtil.getValue(obj, column).toString
        fieldValue.padTo(sizes(column), " ").mkString
      }
      .mkString("| ", " | ", " |" + eol + bottomBorder)
  }

  def appendToFile(topicContent: String, path: Path): Unit = {
    val out: PrintWriter = new PrintWriter(new FileOutputStream(path.toFile, true))
    try {
      out.append(topicContent)
    } finally {
      out.close()
    }
  }

}
