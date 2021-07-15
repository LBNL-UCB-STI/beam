package beam.utils.protocolvis

import beam.utils.protocolvis.Extractors.{AllMessages, ByPerson, ExtractorType}
import beam.utils.protocolvis.MessageReader.RowData
import com.typesafe.scalalogging.StrictLogging
import enumeratum.{Enum, EnumEntry}

import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.time.LocalDateTime
import scala.annotation.tailrec
import scala.collection.immutable

/* Converts beam actor messages to a sequence diagram. This messages are written when the beam config file contains
beam.debug.messageLogging=true
In this case beam produces files like 0.actor_messages_0.csv.gz in each iteration folder.

  Parameters:
--input output/sf-light/run_name__2021-03-29_19-04-50_vnh/ITERS/it.0
--output docs/uml/choose_mode.puml
--diagram-type Sequence | ActorAsState | AgentState | SingleActorAsState
--force
--person-id 010900-2012001379980-0-560057

 input directory where to read message files,
 output the output file,
 diagram-type the type of diagram to generate
 force allows to overwrite the output without prompt
 person-id the person id which the message sequence should be generated for
 */
object VisualizingApp extends StrictLogging {

  def main(args: Array[String]): Unit = {
    parseArgs(args) match {
      case Some(cliOptions) =>
        val confirm = if (cliOptions.forceOverwriting) true else confirmOverwrite(cliOptions.output)
        val extractorType = if (cliOptions.personId.isEmpty) AllMessages else ByPerson(cliOptions.personId)
        if (confirm) doJob(cliOptions.input, cliOptions.output, extractorType, cliOptions.diagramType)
        else println("Exiting...")
      case None => System.exit(1)
    }
  }

  private def doJob(inputFile: Path, output: Path, extractorType: ExtractorType, diagramType: DiagramType): Unit = {
    import java.time.temporal.ChronoUnit.SECONDS
    val startTime = LocalDateTime.now()
    logger.info(s"Generating diagram $diagramType")
    logger.info(s"Start reading from $inputFile")
    val (csvStream, closable) = MessageReader.readData(inputFile)
    if (diagramType.isMultiFileOutput) {
      if (!Files.exists(output))
        Files.createDirectories(output)
      else if (!Files.isDirectory(output))
        throw new IllegalArgumentException(s"$output is not a directory")
    }
    try {
      val extractor = Extractors.messageExtractor(extractorType)
      val processor = appropriateProcessor(diagramType)
      val extracted = extractor(csvStream)
      processor(extracted, output)
    } catch {
      case exception: Throwable =>
        exception.printStackTrace()
    } finally {
      closable.close()
    }
    val endTime = LocalDateTime.now()
    logger.info(s"Exiting, execution time = ${SECONDS.between(startTime, endTime)} seconds, data written to $output")
  }

  private def appropriateProcessor(diagramType: DiagramType): (Iterator[RowData], Path) => Unit =
    diagramType match {
      case DiagramType.Sequence           => SequenceDiagram.process
      case DiagramType.ActorAsState       => ActorAsState.process
      case DiagramType.AgentState         => AgentStateDiagram.process
      case DiagramType.SingleActorAsState => ActorAsState.processForEachActor
    }

  private def confirmOverwrite(path: Path): Boolean = {
    if (Files.exists(path))
      askUserYesNoQuestion("File exits. Overwrite? (Y/n)", default = true)
    else
      true
  }

  @tailrec
  private def askUserYesNoQuestion(question: String, default: Boolean): Boolean = {
    println(question)
    val value = scala.io.StdIn.readLine()
    val answer = if (value.trim.isEmpty) Some(default) else parseYesNoString(value)
    answer match {
      case Some(value) => value
      case None        => askUserYesNoQuestion(question, default)
    }

  }

  private def parseYesNoString(str: String): Option[Boolean] = {
    str.trim.toLowerCase match {
      case "y" | "yes" => Some(true)
      case "n" | "no"  => Some(false)
      case _           => None
    }
  }

  private def parseArgs(args: Array[String]): Option[CliOptions] = {
    import scopt.OParser
    implicit val diagramTypeRead: scopt.Read[DiagramType] = scopt.Read.reads(DiagramType.withNameInsensitive)
    val builder = OParser.builder[CliOptions]
    val parser1 = {
      import builder._
      OParser.sequence(
        programName("beam-protocols"),
        opt[File]('i', "input")
          .required()
          .valueName("<file>")
          .action((x, c) => c.copy(input = x.toPath))
          .text("Directory or file containing csv file with BEAM message sequence"),
        opt[File]('o', "output")
          .required()
          .valueName("<file>")
          .action((x, c) => c.copy(output = x.toPath))
          .text("path where to save the generated puml file"),
        opt[Unit]('f', "force")
          .optional()
          .action((_, c) => c.copy(forceOverwriting = true))
          .text("overwrite output file"),
        opt[String]('p', "person-id")
          .optional()
          .action((x, c) => c.copy(personId = x))
          .text("person id to build the message sequence for"),
        opt[DiagramType]('d', "diagram-type")
          .required()
          .valueName("<diagram type>")
          .action((x, c) => c.copy(diagramType = x))
          .text("Sequence | ActorAsState | AgentState | SingleActorAsState")
      )
    }
    OParser.parse(parser1, args, CliOptions())
  }

  case class CliOptions(
    input: Path = Paths.get("."),
    output: Path = Paths.get("."),
    diagramType: DiagramType = DiagramType.Sequence,
    forceOverwriting: Boolean = false,
    personId: String = ""
  )

  sealed abstract class DiagramType(val isMultiFileOutput: Boolean) extends EnumEntry

  object DiagramType extends Enum[DiagramType] {
    val values: immutable.IndexedSeq[DiagramType] = findValues

    case object Sequence extends DiagramType(isMultiFileOutput = false)
    case object ActorAsState extends DiagramType(isMultiFileOutput = false)
    case object AgentState extends DiagramType(isMultiFileOutput = true)
    case object SingleActorAsState extends DiagramType(isMultiFileOutput = true)
  }

}
