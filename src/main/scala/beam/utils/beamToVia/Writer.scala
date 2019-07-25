package beam.utils.beamToVia

import java.io.{File, PrintWriter}

import beam.utils.beamToVia.viaEvent.ViaEvent

import scala.collection.mutable

object Writer {

  def writeSeq[T](seq: Traversable[T], transform: T => String, outputPath: String): Unit = {
    val pw = new PrintWriter(new File(outputPath))
    seq.foreach(seqItem => pw.println(transform(seqItem)))
    pw.close()
  }

  def writeViaEventsQueue[T](queue: mutable.PriorityQueue[T], transform: T => String, outputPath: String): Unit = {
    Console.println("started writing via events")

    val pw = new PrintWriter(new File(outputPath))
    pw.println("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<events version=\"1.0\">")
    while (queue.nonEmpty) {
      val entry = queue.dequeue()
      pw.println(transform(entry))
    }
    pw.println("</events>")
    pw.close()

    Console.println("via events written into " + outputPath)
  }

  def writeSeqOfString(script: Traversable[String], outputPath: String): Unit = {
    val pw = new PrintWriter(new File(outputPath))
    script.foreach(pw.println)
    pw.close()
  }

  def writeViaEvents(pathLinkEvents: Traversable[ViaEvent], outputPath: String): Unit = {
    val pw = new PrintWriter(new File(outputPath))
    pw.println("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<events version=\"1.0\">")
    pathLinkEvents.foreach(event => pw.println(event.toXml.toString()))
    pw.println("</events>")
    pw.close()

    Console.println("via events written into " + outputPath)
  }

  def writeViaIdFile(typeToIdSeq: mutable.Map[String, mutable.HashSet[String]], outputPath: String): Unit = {
    val pw2 = new PrintWriter(new File(outputPath))
    typeToIdSeq.map { case (k, v) => k + "     " + v.size }.toSeq.sorted.foreach(pw2.println)
    pw2.close()

    Console.println("via IDs written into " + outputPath)
  }

  def writeViaModes(modeToCount: mutable.Map[String, Int], outputPath: String): Unit = {
    val pw2 = new PrintWriter(new File(outputPath))
    modeToCount.map { case (mode, count) => mode + "     " + count }.toSeq.sorted.foreach(pw2.println)
    pw2.close()

    Console.println("via modes written into " + outputPath)
  }

  def writeViaActivities(activityToCount: mutable.Map[String, Int], outputPath: String): Unit = {
    val pw2 = new PrintWriter(new File(outputPath))
    activityToCount.map { case (mode, count) => mode + "     " + count }.toSeq.sorted.foreach(pw2.println)
    pw2.close()

    Console.println("via activities written into " + outputPath)
  }

  def writeViaIdGroupFiles(typeToIdSeq: mutable.Map[String, mutable.HashSet[String]], outputPath: String): Unit = {
    import scala.reflect.io.Directory

    val directory = new Directory(new File(outputPath))
    if (!directory.deleteRecursively()) Console.println("Can not delete directory for vehicle ids")
    directory.createDirectory()

    typeToIdSeq.foreach {
      case (vehicleType, ids) =>
        val pw3 = new PrintWriter(new File(outputPath + "\\" + "group." + vehicleType + ".txt"))
        ids.foreach(pw3.println)
        pw3.close()
    }
  }
}
