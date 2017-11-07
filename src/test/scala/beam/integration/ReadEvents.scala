package beam.integration

import java.io.File

trait ReadEvents{
  def getListTagsFromFile(file: File, mkeyValue: Option[(String, String)] = None,
                          tagToReturn: String,
                          eventType: Option[String] = None): Seq[String]

  def getListTagsFrom(filePath: String, mkeyValue: Option[(String, String)] = None,
                      tagToReturn: String,
                      eventType: Option[String] = None): Seq[String]

  def getLinesFrom(file: File): String
}
