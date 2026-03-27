package beam.utils.csv.writers

import beam.agentsim.agents.vehicles.VehicleEmissions.Emissions.{formatName, EmissionType}
import beam.agentsim.agents.vehicles.VehicleEmissions.{Emissions, EmissionsProfile}
import beam.router.skim.core.{AbstractSkimmerInternal, AbstractSkimmerKey}
import beam.router.skim.core.EmissionsSkimmer.{EmissionsSkimmerInternal, EmissionsSkimmerKey}
import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.utils.io.IOUtils

import scala.collection.mutable
import scala.util.control.NonFatal

class EmissionsSkimTotalsWriter extends LazyLogging {

  def write(
    skim: collection.Map[AbstractSkimmerKey, AbstractSkimmerInternal],
    filePath: String,
    expansionFactor: Double,
    pollutantOrder: IndexedSeq[EmissionType]
  ): Unit = {
    require(expansionFactor > 0, s"Expected positive expansion factor, got $expansionFactor")

    val totals = EmissionsSkimTotalsWriter.aggregateTotals(skim, expansionFactor)
    val pollutantIndexes = EmissionsSkimTotalsWriter.pollutantIndexes(pollutantOrder)

    var writer = null: java.io.BufferedWriter
    try {
      writer = IOUtils.getBufferedWriter(filePath)
      writer.write(EmissionsSkimTotalsWriter.header(pollutantOrder) + "\n")
      totals.foreach { case (key, pollutantTotals) =>
        writer.write(EmissionsSkimTotalsWriter.toCsv(key, pollutantTotals, pollutantIndexes))
        writer.write("\n")
      }
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
  private val pollutantToIndex: Map[EmissionType, Int] = defaultPollutantOrder.zipWithIndex.toMap

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

  def pollutantOrderFromFilter(pollutantsFilter: String): IndexedSeq[EmissionType] = {
    val configuredPollutants = Option(pollutantsFilter).toSeq
      .flatMap(_.split(","))
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(Emissions.fromString)
      .toIndexedSeq

    if (configuredPollutants.nonEmpty) configuredPollutants else defaultPollutantOrder
  }

  def header(pollutantOrder: IndexedSeq[EmissionType]): String =
    (Seq("linkId", "vehicleTypeId", "process") ++ pollutantOrder.map(formatName)).mkString(",")

  private[csv] def pollutantIndexes(pollutantOrder: IndexedSeq[EmissionType]): IndexedSeq[Int] =
    pollutantOrder.map(pollutantToIndex)

  private[csv] def aggregateTotals(
    skim: collection.Map[AbstractSkimmerKey, AbstractSkimmerInternal],
    expansionFactor: Double
  ): mutable.LinkedHashMap[TotalsKey, Array[Double]] = {
    require(expansionFactor > 0, s"Expected positive expansion factor, got $expansionFactor")

    val accumulatedTotals = mutable.LinkedHashMap.empty[TotalsKey, Array[Double]]

    skim.foreach {
      case (key: EmissionsSkimmerKey, value: EmissionsSkimmerInternal) =>
        val totalsKey = TotalsKey(key.linkId, key.vehicleTypeId, key.emissionsProcess)
        val pollutantTotals =
          accumulatedTotals.getOrElseUpdate(totalsKey, Array.fill[Double](defaultPollutantOrder.size)(0.0))
        val observations = value.observations.toDouble

        value.emissions.values.foreach { case (pollutant, pollutantValue) =>
          pollutantTotals(pollutantToIndex(pollutant)) += pollutantValue * observations * expansionFactor
        }
      case _ =>
    }

    accumulatedTotals
  }

  private[csv] def toCsv(
    key: TotalsKey,
    pollutantTotals: Array[Double],
    pollutantIndexes: IndexedSeq[Int]
  ): String = {
    val row = new java.lang.StringBuilder
    row.append(key.linkId)
    row.append(',')
    row.append(key.vehicleTypeId)
    row.append(',')
    row.append(key.process.toString)

    pollutantIndexes.foreach { pollutantIndex =>
      row.append(',')
      row.append(pollutantTotals(pollutantIndex))
    }

    row.toString
  }

  def totalsRows(
    skim: collection.Map[AbstractSkimmerKey, AbstractSkimmerInternal],
    expansionFactor: Double,
    pollutantOrder: IndexedSeq[EmissionType] = defaultPollutantOrder
  ): IndexedSeq[TotalsRow] = {
    val indexes = pollutantIndexes(pollutantOrder)
    aggregateTotals(skim, expansionFactor).iterator.map { case (key, pollutantTotals) =>
      val emissions = pollutantOrder.zip(indexes).collect {
        case (pollutant, index) if pollutantTotals(index) != 0.0 => pollutant -> pollutantTotals(index)
      }
      TotalsRow(key.linkId, key.vehicleTypeId, key.process, Emissions(emissions: _*), pollutantOrder)
    }.toIndexedSeq
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
