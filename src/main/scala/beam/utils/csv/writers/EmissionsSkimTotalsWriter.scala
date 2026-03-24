package beam.utils.csv.writers

import beam.agentsim.agents.vehicles.VehicleEmissions.Emissions.{EmissionType, formatName, init}
import beam.agentsim.agents.vehicles.VehicleEmissions.{Emissions, EmissionsProfile}
import beam.router.skim.core.{AbstractSkimmerInternal, AbstractSkimmerKey}
import beam.router.skim.core.EmissionsSkimmer.{EmissionsSkimmerInternal, EmissionsSkimmerKey}
import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.utils.io.IOUtils

import scala.util.control.NonFatal

class EmissionsSkimTotalsWriter extends LazyLogging {

  def write(
    skim: collection.Map[AbstractSkimmerKey, AbstractSkimmerInternal],
    filePath: String,
    expansionFactor: Double,
    pollutantOrder: IndexedSeq[EmissionType]
  ): Unit = {
    require(expansionFactor > 0, s"Expected positive expansion factor, got $expansionFactor")

    var writer = null: java.io.BufferedWriter
    try {
      writer = IOUtils.getBufferedWriter(filePath)
      writer.write(EmissionsSkimTotalsWriter.header(pollutantOrder) + "\n")
      EmissionsSkimTotalsWriter
        .totalsRows(skim, expansionFactor, pollutantOrder)
        .foreach(row => writer.write(row.toCsv + "\n"))
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Could not write emissions totals in '$filePath': ${ex.getMessage}", ex)
    } finally {
      if (writer != null) writer.close()
    }
  }
}

object EmissionsSkimTotalsWriter {

  final val fileBaseName = "skimsEmissionsTotals"
  final val fileName = s"$fileBaseName.csv.gz"
  private val defaultPollutantOrder: IndexedSeq[EmissionType] = Emissions.values.toIndexedSeq

  def pollutantOrderFromFilter(pollutantsFilter: String): IndexedSeq[EmissionType] = {
    val configuredPollutants = Option(pollutantsFilter)
      .toSeq
      .flatMap(_.split(","))
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(Emissions.fromString)
      .toIndexedSeq

    if (configuredPollutants.nonEmpty) configuredPollutants else defaultPollutantOrder
  }

  def header(pollutantOrder: IndexedSeq[EmissionType]): String =
    (Seq("linkId", "vehicleTypeId", "process") ++ pollutantOrder.map(formatName)).mkString(",")

  case class TotalsKey(linkId: Int, vehicleTypeId: String, process: EmissionsProfile.EmissionsProcess)

  case class TotalsRow(
    linkId: Int,
    vehicleTypeId: String,
    process: EmissionsProfile.EmissionsProcess,
    emissions: Emissions,
    pollutantOrder: IndexedSeq[EmissionType]
  ) {
    def toCsv: String = {
      val pollutantValues = pollutantOrder.map(pollutant => emissions.values.getOrElse(pollutant, 0.0).toString)
      (Seq(linkId.toString, vehicleTypeId, process.toString) ++ pollutantValues).mkString(",")
    }
  }

  def totalsRows(
    skim: collection.Map[AbstractSkimmerKey, AbstractSkimmerInternal],
    expansionFactor: Double,
    pollutantOrder: IndexedSeq[EmissionType] = defaultPollutantOrder
  ): IndexedSeq[TotalsRow] = {
    require(expansionFactor > 0, s"Expected positive expansion factor, got $expansionFactor")

    skim.collect { case (key: EmissionsSkimmerKey, value: EmissionsSkimmerInternal) => key -> value }
      .groupBy { case (key, _) => TotalsKey(key.linkId, key.vehicleTypeId, key.emissionsProcess) }
      .map { case (key, values) =>
        val observedTotals = values.foldLeft(init() -> 0) { case ((emissionsAcc, observationsAcc), (_, value)) =>
          (emissionsAcc + value.emissions * value.observations.toDouble, observationsAcc + value.observations)
        }
        val scaledTotals = observedTotals._1 * expansionFactor
        TotalsRow(key.linkId, key.vehicleTypeId, key.process, scaledTotals, pollutantOrder)
      }
      .toIndexedSeq
      .sortBy(row => (row.linkId, row.vehicleTypeId, row.process.toString))
  }

  def iterationOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("EmissionsSkimTotalsWriter", fileName, iterationLevel = true)(
      """
        linkId | Link ID
        vehicleTypeId | Vehicle type id
        process | Emissions process (RUNEX, IDLEX, STREX, DIURN, HOTSOAK, RUNLOSS, PMTW, PMBW, PRDUST)
        pollutant columns | Population-scaled total emissions for each pollutant listed in beam.agentsim.agents.vehicles.emissions.pollutantsFilter; if the filter is empty, all pollutants are written
        """
    )
}
