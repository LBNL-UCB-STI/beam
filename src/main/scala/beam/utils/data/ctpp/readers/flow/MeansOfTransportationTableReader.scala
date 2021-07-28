package beam.utils.data.ctpp.readers.flow

import beam.utils.data.ctpp.models.{FlowGeoParser, MeansOfTransportation, OD, ResidenceToWorkplaceFlowGeography}
import beam.utils.data.ctpp.readers.BaseTableReader
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData, Table}

class MeansOfTransportationTableReader(
  dbInfo: CTPPDatabaseInfo,
  val residenceToWorkplaceFlowGeography: ResidenceToWorkplaceFlowGeography
) extends BaseTableReader(dbInfo, Table.MeanOfTransportation, Some(residenceToWorkplaceFlowGeography.level)) {
  private val interestedLineNumber: Set[Int] = MeansOfTransportation.all.map(_.lineNumber).toSet

  def read(): Iterable[OD[MeansOfTransportation]] = {
    readRaw()
      .filter(x => interestedLineNumber.contains(x.lineNumber))
      .map { entry =>
        val (fromGeoId, toGeoId) = FlowGeoParser.parse(entry.geoId)
        val mode = MeansOfTransportation(entry.lineNumber).get
        OD(fromGeoId, toGeoId, mode, entry.estimate)
      }
  }
}

object MeansOfTransportationTableReader {
  val counties: Set[String] = Set("48021", "48053", "48055", "48209", "48453", "48491")

  def tazFilter(od: OD[MeansOfTransportation]): Boolean = {
    counties.exists(c => od.source.startsWith(c)) && counties.exists(c => od.destination.startsWith(c))
  }

  def countyFilter(od: OD[MeansOfTransportation]): Boolean = {
    counties.contains(od.source) && counties.contains(od.destination)
  }

  def main(args: Array[String]): Unit = {
    val databaseInfo = CTPPDatabaseInfo(PathToData("d:/Work/beam/CTPP"), Set("48"))
    val ods: Iterable[OD[MeansOfTransportation]] =
      new MeansOfTransportationTableReader(databaseInfo, ResidenceToWorkplaceFlowGeography.`TAZ To TAZ`)
        .read()

    println("Overall modes:")
    calcModes(ods)

    val interestedCounties = ods.filter(countyFilter)
    println(s"Interested counties: $counties")
    calcModes(interestedCounties)

    val interestedCountiesSum = interestedCounties.map(_.value).sum.toLong
    val overallSum = ods.map(_.value).sum.toLong
    println(s"interestedCountiesSum: $interestedCountiesSum")
    println(s"overallSum: $overallSum")
    println(s"Read ${ods.size} OD pairs")
  }

  def calcModes(ods: Iterable[OD[MeansOfTransportation]]): Unit = {
    val modeToSum = ods
      .groupBy(x => x.attribute.toBeamMode)
      .map { case (mode, xs) =>
        mode -> xs.map(_.value).sum.toLong
      }
      .toSeq
      .sortBy(x => x._1.map(_.value).getOrElse(""))
    val totalModes = modeToSum.map(_._2).sum
    println(s"The sum of all modes: $totalModes")
    modeToSum.foreach { case (mode, sum) =>
      val pct = (100 * sum.toDouble / totalModes).formatted("%.2f")
      println(s"$mode => $sum, $pct %")
    }
  }
}
