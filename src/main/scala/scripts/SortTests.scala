package scripts

import java.util.concurrent.TimeUnit

import scala.concurrent.duration._

/*
How to use this script:
Save the output of tests to a file. For example testsOutput.txt
Run SortTests class passing testOutput.txt path as parameter and the script will sort in descending order
 */
object SortTests extends App {
  val minutePattern = "([0-9]+?) minute( )??".r
  val secondPattern = "([0-9]+?) second( )?".r
  val millisPattern = "([0-9]+?) millisecond( )?".r
  val descriptionPattern = "(.*?)\\([0-9]+? .*?\\)??".r

  def parseLine(text: String): (Duration, String) = {
    val minutes = minutePattern
      .findAllMatchIn(text)
      .toList
      .headOption
      .map(x => Duration(x.group(1).toInt, TimeUnit.MINUTES))
      .getOrElse(Duration.Zero)
    val seconds = secondPattern
      .findAllMatchIn(text)
      .toList
      .headOption
      .map(x => Duration(x.group(1).toInt, TimeUnit.SECONDS))
      .getOrElse(Duration.Zero)
    val millis = millisPattern
      .findAllMatchIn(text)
      .toList
      .headOption
      .map(x => Duration(x.group(1).toInt, TimeUnit.MILLISECONDS))
      .getOrElse(Duration.Zero)

    val description = descriptionPattern
      .findAllMatchIn(text)
      .toList
      .headOption
      .map(_.group(1).trim)
      .getOrElse("could not extract from: " + text)

    (minutes + seconds + millis, description)
  }

  val source = scala.io.Source.fromFile(args(0))

  val allLines =
    try source.getLines.toList
    finally source.close()

  val tests = allLines.filter(l => l.toLowerCase.contains("second") || l.toLowerCase.contains("minute"))
  val testsSortedDesc = tests.map(parseLine).sortWith(_._1 > _._1)

  println(testsSortedDesc.mkString("\n"))
}
