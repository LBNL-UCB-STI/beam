package beam.utils.beam_to_matsim.io

import java.io.{File, PrintWriter}

import beam.utils.FileUtils
import beam.utils.beam_to_matsim.via_event.ViaEvent

import scala.collection.mutable

object Writer {

  def writeSeq[T](seq: Traversable[T], transform: T => String, outputPath: String): Unit = {
    FileUtils.using(new PrintWriter(new File(outputPath))) { pw =>
      seq.foreach(seqItem => pw.println(transform(seqItem)))
    }
  }

  def writeViaEventsQueue[T](queue: mutable.PriorityQueue[T], transform: T => String, outputPath: String): Unit = {
    val eventsCount = queue.size
    Console.println(s"started writing $eventsCount events ...")
    val progress = new ConsoleProgress("evenst written", eventsCount, 5)

    FileUtils.using(new PrintWriter(new File(outputPath))) { pw =>
      pw.println("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<events version=\"1.0\">")
      while (queue.nonEmpty) {
        progress.step()
        val entry = queue.dequeue()
        pw.println(transform(entry))
      }
      pw.println("</events>")
    }

    progress.finish()

    Console.println("via events written into " + outputPath)
  }

  def writeSeqOfString(script: Traversable[String], outputPath: String): Unit = {
    FileUtils.using(new PrintWriter(new File(outputPath))) { pw =>
      script.foreach(pw.println)
    }
  }

  def writeViaEvents(pathLinkEvents: Traversable[ViaEvent], outputPath: String): Unit = {
    FileUtils.using(new PrintWriter(new File(outputPath))) { pw =>
      pw.println("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<events version=\"1.0\">")
      pathLinkEvents.foreach(event => pw.println(event.toXml.toString()))
      pw.println("</events>")
    }

    Console.println("via events written into " + outputPath)
  }

  def writeViaIdFile(typeToIdSeq: mutable.Map[String, mutable.HashSet[String]], outputPath: String): Unit = {
    FileUtils.using(new PrintWriter(new File(outputPath))) { pw =>
      typeToIdSeq.map { case (k, v) => k + "     " + v.size }.toSeq.sorted.foreach(pw.println)
    }

    Console.println("via IDs written into " + outputPath)
  }

  def writeViaIdsAll(typeToIdSeq: mutable.Map[String, mutable.HashSet[String]], outputBasePath: String): Unit = {
    FileUtils.using(new PrintWriter(new File(outputBasePath + ".all.txt"))) { pw =>
      typeToIdSeq.map { case (k, v) => k + "     " + v.size }.toSeq.sorted.foreach(pw.println)
    }

    new File(outputBasePath).mkdir()

    typeToIdSeq.foreach { case (group, ids) =>
      FileUtils.using(new PrintWriter(new File(outputBasePath + "/" + group + ".txt"))) { pw =>
        ids.toSeq.sorted.foreach(pw.println)
      }
    }

    Console.println("all via IDs written into " + outputBasePath)
  }

  def writeViaModes(modeToCount: mutable.Map[String, Int], outputPath: String): Unit = {
    FileUtils.using(new PrintWriter(new File(outputPath))) { pw =>
      modeToCount.map { case (mode, count) => mode + "     " + count }.toSeq.sorted.foreach(pw.println)
    }

    Console.println("via modes written into " + outputPath)
  }

  def writeViaActivities(activityToCount: mutable.Map[String, Int], outputPath: String): Unit = {
    FileUtils.using(new PrintWriter(new File(outputPath))) { pw =>
      activityToCount.map { case (mode, count) => mode + "     " + count }.toSeq.sorted.foreach(pw.println)
    }

    Console.println("via activities written into " + outputPath)
  }

  def writeViaIdGroupFiles(typeToIdSeq: mutable.Map[String, mutable.HashSet[String]], outputPath: String): Unit = {
    import scala.reflect.io.Directory

    val directory = new Directory(new File(outputPath))
    if (!directory.deleteRecursively()) Console.println("Can not delete directory for vehicle ids")
    directory.createDirectory()

    typeToIdSeq.foreach { case (vehicleType, ids) =>
      FileUtils.using(new PrintWriter(new File(outputPath + "\\" + "group." + vehicleType + ".txt"))) { pw =>
        ids.foreach(pw.println)
      }
    }
  }
}
