package beam.utils.data.synthpop.generators

import beam.utils.ProfilingUtils
import beam.utils.data.ctpp.models.{OD, ResidenceToWorkplaceFlowGeography}
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData}
import beam.utils.data.ctpp.readers.flow.TimeLeavingHomeTableReader
import com.typesafe.scalalogging.StrictLogging

trait TimeLeavingHomeGenerator {
  def find(source: String, destination: String): Seq[OD[Range]]
}

class TimeLeavingHomeGeneratorImpl(
  val dbInfo: CTPPDatabaseInfo,
  val residenceToWorkplaceFlowGeography: ResidenceToWorkplaceFlowGeography
) extends TimeLeavingHomeGenerator
    with StrictLogging {

  private val sourceToTimeLeavingOD: Map[String, Iterable[OD[Range]]] =
    new TimeLeavingHomeTableReader(dbInfo, residenceToWorkplaceFlowGeography).read().groupBy(x => x.source)

  private val srcDstToTimeLeavingOD: Map[(String, String), Seq[OD[Range]]] =
    ProfilingUtils.timed("Created `srcDstToTimeLeavingOD` map", x => logger.info(x)) {
      sourceToTimeLeavingOD.toSeq
        .flatMap {
          case (source, xs) =>
            xs.map { od =>
              (source, od.destination) -> od
            }
        }
        .groupBy { case ((src, dst), _) => (src, dst) }
        .map {
          case (srcDst, xs) =>
            srcDst -> xs.map(_._2).sortBy(x => x.attribute.start)
        }
    }

  def find(source: String, destination: String): Seq[OD[Range]] = {
    val key = (source, destination)
    srcDstToTimeLeavingOD.getOrElse(key, Seq.empty)
  }
}
