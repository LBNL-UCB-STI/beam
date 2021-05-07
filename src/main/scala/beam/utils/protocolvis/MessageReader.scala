package beam.utils.protocolvis

import beam.utils.csv.GenericCsvReader
import com.typesafe.scalalogging.LazyLogging

import java.io.Closeable
import java.nio.file.{Files, Path}
import java.util
import scala.util.Try

/**
  * @author Dmitry Openkov
  */
object MessageReader extends LazyLogging {

  def readData(path: Path): (Iterator[RowData], Closeable) = {

    def readSingleFile(path: Path): (Iterator[RowData], Closeable) = {
      GenericCsvReader.readAs[RowData](path.toString, rowToRowData, _ => true)
    }

    def findFiles(dir: Path): Seq[Path] = {
      val FileNum = """actor_messages_(\d+)""".r.unanchored
      import scala.jdk.CollectionConverters._
      val paths = Files.newDirectoryStream(dir, "*.actor_messages_*.csv.gz").iterator().asScala.toSeq
      val pathWithNum = paths
        .map(path => path -> path.getFileName.toString)
        .collect {
          case (path, FileNum(num)) => path -> num.toInt
        }
      val sorted = pathWithNum.sortBy { case (_, num) => num }
      sorted.map { case (path, _) => path }
    }

    def readFromDir(path: Path): (Iterator[RowData], Closeable) = {
      val files: Seq[Path] = findFiles(path)
      val fileIterSeq: Seq[(Iterator[RowData], Closeable)] = files.map(readSingleFile)
      val (iterator, closables) = fileIterSeq.foldLeft((Iterator[RowData](), List.empty[Closeable])) {
        case ((accIt, acc), (it, closable)) => (accIt ++ it, acc :+ closable)
      }
      (iterator, () => closables.foreach(closable => Try(closable.close())))
    }

    val isDirectory = Files.isDirectory(path)
    if (isDirectory) readFromDir(path) else readSingleFile(path)

  }

  private def escapeNull(str: String) = if (str == null) "" else str

  private def rowToRowData(row: java.util.Map[String, String]): RowData = {
    def extractActor(record: util.Map[String, String], position: String) = {
      val parent = escapeNull(record.get(s"${position}_parent"))
      val name = escapeNull(record.get(s"${position}_name"))
      Actor(parent, name)
    }

    def getTick(record: util.Map[String, String]) = {
      val tickStr = record.get("tick")
      if (tickStr == null || tickStr == "") -1
      else if (tickStr.contains('.')) Math.round(tickStr.toDouble).toInt
      else tickStr.toInt
    }

    def getTriggerId(record: util.Map[String, String]) = {
      val triggerIdStr = record.get("triggerId")
      if (triggerIdStr == null || triggerIdStr == "") -1
      else triggerIdStr.toLong
    }

    row.get("type") match {
      case "message" =>
        Message(
          extractActor(row, "sender"),
          extractActor(row, "receiver"),
          row.get("payload"),
          getTick(row),
          getTriggerId(row)
        )
      case "event" =>
        Event(
          extractActor(row, "sender"),
          extractActor(row, "receiver"),
          row.get("payload"),
          row.get("state"),
          getTick(row),
          getTriggerId(row)
        )
      case "transition" =>
        Transition(
          extractActor(row, "sender"),
          extractActor(row, "receiver"),
          row.get("payload"),
          row.get("state"),
          getTick(row),
          getTriggerId(row)
        )
      case x @ _ =>
        logger.error("Receiving a wrong message type: {}", x)
        throw new IllegalArgumentException(s"Cannot handle row: $row")
    }
  }

  sealed abstract class RowData {
    def sender: Actor

    def receiver: Actor

    def triggerId: Long
  }

  case class Event(
    sender: Actor,
    receiver: Actor,
    payload: String,
    data: String,
    tick: Int,
    triggerId: Long,
  ) extends RowData

  case class Message(
    sender: Actor,
    receiver: Actor,
    payload: String,
    tick: Int,
    triggerId: Long,
  ) extends RowData

  case class Transition(
    sender: Actor,
    receiver: Actor,
    prevState: String,
    state: String,
    tick: Int,
    triggerId: Long,
  ) extends RowData

  case class Actor(parent: String, name: String)

}
