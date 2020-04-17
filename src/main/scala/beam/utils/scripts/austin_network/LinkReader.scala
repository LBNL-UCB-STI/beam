package beam.utils.scripts.austin_network

import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.network.Link
import scala.io.Source
case class LinkDetails(
  linkChainId: Id[Link],
  length: Double,
  speed: Double,
  capacity: Int,
  lanes: Int,
  geometry: List[Coord]
)

object LinkReader {

  val skipLine = 6 // 5 for information and 1 for header
  def main(args: Array[String]): Unit = {
    val detailFilePath = "/home/rajnikant/Desktop/austin-data/austin.2015_regional_am.public.linkdetails.csv"
    val publicLinkPath = "/home/rajnikant/Desktop/austin-data/austin.2015_regional_am.public.links.csv"
    val publicLinkSource = Source.fromFile(publicLinkPath)
    val publicLink = publicLinkSource.getLines().drop(skipLine).map(linkGeometry).toMap
    publicLinkSource.close()

    val detailLinkSource = Source.fromFile(detailFilePath)
    val linkDetails = detailLinkSource.getLines().drop(skipLine).map(linkDetail(publicLink))

    linkDetails.foreach(linkDetails => println(linkDetails))
    detailLinkSource.close()
  }

  def linkDetail(linkGeometry: Map[Id[Link], List[Coord]])(row: String): LinkDetails = {
    val tokens = row.replaceAll("\"", "").split(",")
    val linkId = Id.createLinkId(tokens(0))
    LinkDetails(
      linkId,
      tokens(4).toDouble,
      tokens(5).toDouble,
      tokens(6).toDouble.toInt,
      tokens(7).toDouble.toInt,
      linkGeometry(linkId)
    )
  }

  def linkGeometry(row: String): (Id[Link], List[Coord]) = {
    val list = row
      .replaceAll("\"", "")
      .replace("[", "")
      .replace("]", "")
      .split(",", 2)
    (Id.createLinkId(list(0)), value(list(1)))
  }

  def value(str: String): List[Coord] = {
    val coordString = str.split("\\),\\(")

    coordString
      .map(
        coord =>
          coord
            .replaceAll("\\(", "")
            .replaceAll("\\)", "")
            .split(",")
      )
      .map(c => new Coord(c(0).toDouble, c(1).toDouble))
      .toList
  }
}
