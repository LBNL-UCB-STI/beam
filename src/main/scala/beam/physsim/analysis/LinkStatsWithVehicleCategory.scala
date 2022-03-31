package beam.physsim.analysis

import beam.utils.BeamCalcLinkStats
import beam.utils.BeamCalcLinkStats.LinkData
import beam.utils.csv.CsvWriter
import org.matsim.analysis.VolumesAnalyzer
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup
import org.matsim.core.router.util.TravelTime

import scala.collection.JavaConverters._
import scala.util.Try

/**
  * @author Dmitry Openkov
  */
class LinkStatsWithVehicleCategory(
  network: Network,
  ttConfigGroup: TravelTimeCalculatorConfigGroup
) {

  def calculateLinkData(
    volumesAnalyzer: VolumesAnalyzer,
    travelTimeForR5: TravelTime,
    categories: IndexedSeq[String]
  ): (Map[Id[Link], LinkData], Map[String, Map[Id[Link], LinkData]], Int) = {
    val calc = new BeamCalcLinkStats(network, ttConfigGroup)
    calc.reset()
    calc.addData(volumesAnalyzer, travelTimeForR5)
    val totalLinkData = calc.getLinkData.asScala.toMap
    val linkData = categories.map { category =>
      calc.reset()
      calc.addData(volumesAnalyzer, travelTimeForR5, category)
      category -> calc.getLinkData.asScala.toMap
    }.toMap
    (totalLinkData, linkData, calc.getNofHours)
  }

  def writeToFile(
    totalLinkData: Map[Id[Link], BeamCalcLinkStats.LinkData],
    linkData: Map[String, Map[Id[Link], BeamCalcLinkStats.LinkData]],
    nofHours: Int,
    aggregation: Seq[(Seq[String], String)],
    filePath: String
  ): Try[Unit] = {
    val header = Seq("link", "from", "to", "hour", "length", "freespeed", "capacity", "stat", "volume") ++
      aggregation.map(_._2) :+ "traveltime"
    val csvWriter = new CsvWriter(filePath, header)
    val rows = totalLinkData.view.flatMap { case (linkId, data) =>
      val link = network.getLinks.get(linkId)
      for {
        hour <- 0 until nofHours
      } yield {
        val categoryVolumes = aggregation.map { case (categories, _) =>
          categories.map(category => linkData(category).get(linkId).map(_.getSumVolume(hour)).getOrElse(0.0)).sum
        }
        Seq(
          linkId,
          link.getFromNode.getId,
          link.getToNode.getId,
          hour,
          link.getLength,
          link.getFreespeed,
          link.getCapacity,
          "AVG",
          totalLinkData.get(linkId).map(_.getSumVolume(hour)).getOrElse(0.0)
        ) ++ categoryVolumes ++ Seq(data.calculateAverageTravelTime(hour))
      }

    }
    csvWriter.writeAllAndClose(rows)
  }

  def writeLinkStatsWithTruckVolumes(
    volumesAnalyzer: VolumesAnalyzer,
    travelTimeForR5: TravelTime,
    filePath: String
  ): Try[Unit] = {
    val categoryMapping = IndexedSeq(
      Seq("LightDutyTruck", "HeavyDutyTruck") -> "TruckVolume",
      Seq("HeavyDutyTruck")                   -> "HDTruckVolume"
    )
    val (totalLinkData, linkData, nofHours) =
      calculateLinkData(volumesAnalyzer, travelTimeForR5, categoryMapping.flatMap(_._1))
    writeToFile(totalLinkData, linkData, nofHours, categoryMapping, filePath)
  }
}
