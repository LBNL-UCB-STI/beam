package beam.utils.protocolvis

import beam.utils.protocolvis.Extractors.{AllMessages, ByPerson, ExtractorType}
import com.typesafe.scalalogging.StrictLogging

import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.time.LocalDateTime
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

/* Converts beam actor messages to a sequence diagram. This messages are written when the beam config file contains
akka.actor.debug.receive=true
In this case beam produces files like 0.actor_messages_0.csv.gz in each iteration folder.

Order of the messages is not always right because each actor has its own message queue.

  Parameters:
--input output/sf-light/run_name__2021-03-29_19-04-50_vnh/ITERS/it.0
--output docs/uml/choose_mode.puml
--force
--person-id 010900-2012001379980-0-560057

 input directory where to read message files,
 output the output file,
 force allows to overwrite the output without prompt
 person-id the person id which the message sequence should be generated for
  */
object VisualizingApp extends StrictLogging {

  def main(args: Array[String]): Unit = {
    parseArgs(args) match {
      case Some(cliOptions) =>
        val confirm = confirmOverwrite(cliOptions.output, cliOptions.forceOverwriting)
        val extractorType = if (cliOptions.personId.isEmpty) AllMessages else ByPerson(cliOptions.personId)
        if (confirm) doJob(cliOptions.input, cliOptions.output, extractorType) else println("Exiting...")
      case None =>
    }
  }

  private def doJob(inputFile: Path, output: Path, extractorType: ExtractorType): Unit = {
    import java.time.temporal.ChronoUnit.SECONDS
    val startTime = LocalDateTime.now()
    logger.info(s"Start reading from $inputFile")
    val (csvStream, closable) = MessageReader.readData(inputFile)
    val extractor = Extractors.messageExtractor(extractorType)
    val triedExtracted = Try(extractor(csvStream))
    Try(closable.close())
    triedExtracted match {
      case Failure(exception) =>
        println(exception.getMessage)
      case Success(extracted) =>
        val puml: IndexedSeq[SequenceDiagram.PumlEntry] = SequenceDiagram.processMessages(extracted)
        PumlWriter.writeData(puml, output)(SequenceDiagram.serializer)
    }
    val endTime = LocalDateTime.now()
    logger.info(s"Exiting, execution time = ${SECONDS.between(startTime, endTime)} seconds")
  }

  private def confirmOverwrite(path: Path, force: Boolean): Boolean = {
    val exists = if (force) false else Files.exists(path)
    if (exists) askUserYesNoQuestion("File exits. Overwrite? (Y/n)", default = true) else true
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
      case "y"   => Some(true)
      case "yes" => Some(true)
      case "n"   => Some(false)
      case "no"  => Some(false)
      case _     => None
    }
  }

  private def parseArgs(args: Array[String]): Option[CliOptions] = {
    import scopt.OParser
    val builder = OParser.builder[CliOptions]
    val parser1 = {
      import builder._
      OParser.sequence(
        programName("beam-protocols"),
        opt[File]('i', "input")
          .required()
          .valueName("<file>")
          .action((x, c) => c.copy(input = x.toPath))
          .text("csv file with BEAM message sequence"),
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
      )
    }
    OParser.parse(parser1, args, CliOptions())
  }

  case class CliOptions(
    input: Path = Paths.get("."),
    output: Path = Paths.get("."),
    forceOverwriting: Boolean = false,
    personId: String = "",
  )

}
