package beam.router.skim.core

import beam.agentsim.agents.vehicles.VehicleEmissions.Emissions._
import beam.agentsim.agents.vehicles.VehicleEmissions.{Emissions, EmissionsProfile}
import beam.router.skim.{readonly, Skims}
import beam.sim.config.BeamConfig
import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.controler.MatsimServices

class EmissionsSkimmer @Inject() (matsimServices: MatsimServices, beamConfig: BeamConfig)
    extends AbstractSkimmer(beamConfig, matsimServices.getControlerIO) {
  import EmissionsSkimmer._
  private val config: BeamConfig.Beam.Router.Skim = beamConfig.beam.router.skim

  override lazy val readOnlySkim: AbstractSkimmerReadOnly = new readonly.EmissionsSkims()

  override protected val skimName: String = config.emissions_skimmer.name
  override protected val skimType: Skims.SkimType.Value = Skims.SkimType.EMISSIONS_SKIMMER
  override protected val skimFileBaseName: String = config.emissions_skimmer.fileBaseName

  override protected val skimFileHeader: String = {
    s"hour,linkId,vehicleTypeId,process,emissions,travelTimeInSecond,parkingDurationInSecond,observations,iterations"
  }

  override def fromCsv(
    line: scala.collection.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    val emissionsMap = line
      .getOrElse("emissions", "")
      .split(";")
      .filter(_.nonEmpty)
      .map { entry =>
        val parts = entry.split(":")
        if (parts.length == 2) {
          try {
            Some(Emissions.withName(parts(0)) -> parts(1).toDouble)
          } catch {
            case _: Exception => None
          }
        } else None
      }
      .collect { case Some(kv) => kv }
      .toMap

    (
      EmissionsSkimmerKey(
        line("linkId").toInt,
        line("vehicleTypeId"),
        line("hour").toInt,
        EmissionsProfile.withName(line("process"))
      ),
      EmissionsSkimmerInternal(
        Emissions(emissionsMap),
        line("travelTimeInSecond").toDouble,
        line("parkingDurationInSecond").toDouble,
        line("observations").toInt,
        line("iterations").toInt
      )
    )
  }

  override protected def aggregateOverIterations(
    prevIteration: Option[AbstractSkimmerInternal],
    currIteration: Option[AbstractSkimmerInternal]
  ): AbstractSkimmerInternal = {
    val prevSkim = prevIteration
      .map(_.asInstanceOf[EmissionsSkimmerInternal])
      .getOrElse(EmissionsSkimmerInternal(init(), 0, 0, 0))
    val currSkim = currIteration
      .map(_.asInstanceOf[EmissionsSkimmerInternal])
      .getOrElse(
        EmissionsSkimmerInternal(init(), 0, 0, 0, iterations = matsimServices.getIterationNumber + 1)
      )
    EmissionsSkimmerInternal(
      emissions =
        (prevSkim.emissions * prevSkim.iterations + currSkim.emissions * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
      travelTime =
        (prevSkim.travelTime * prevSkim.iterations + currSkim.travelTime * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
      parkingDuration =
        (prevSkim.parkingDuration * prevSkim.iterations + currSkim.parkingDuration * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
      observations =
        (prevSkim.observations * prevSkim.iterations + currSkim.observations * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
      iterations = prevSkim.iterations + currSkim.iterations
    )
  }

  override protected def aggregateWithinIteration(
    prevObservation: Option[AbstractSkimmerInternal],
    currObservation: AbstractSkimmerInternal
  ): AbstractSkimmerInternal = {
    val prevSkim = prevObservation
      .map(_.asInstanceOf[EmissionsSkimmerInternal])
      .getOrElse(EmissionsSkimmerInternal(init(), 0, 0, 0, iterations = matsimServices.getIterationNumber + 1))
    val currSkim = currObservation.asInstanceOf[EmissionsSkimmerInternal]
    EmissionsSkimmerInternal(
      emissions =
        (prevSkim.emissions * prevSkim.observations + currSkim.emissions * currSkim.observations) / (prevSkim.observations + currSkim.observations),
      travelTime =
        (prevSkim.travelTime * prevSkim.observations + currSkim.travelTime * currSkim.observations) / (prevSkim.observations + currSkim.observations),
      parkingDuration =
        (prevSkim.parkingDuration * prevSkim.observations + currSkim.parkingDuration * currSkim.observations) / (prevSkim.observations + currSkim.observations),
      observations = prevSkim.observations + currSkim.observations,
      iterations = prevSkim.iterations
    )
  }
}

object EmissionsSkimmer extends LazyLogging {

  case class EmissionsSkimmerKey(
    linkId: Int,
    vehicleTypeId: String,
    hour: Int,
    emissionsProcess: EmissionsProfile.EmissionsProcess
  ) extends AbstractSkimmerKey {
    override def toCsv: String = s"$hour,$linkId,$vehicleTypeId,${emissionsProcess.toString}"
  }

  case class EmissionsSkimmerInternal(
    emissions: Emissions,
    travelTime: Double,
    parkingDuration: Double,
    observations: Int = 0,
    iterations: Int = 0
  ) extends AbstractSkimmerInternal {
    // Replace this line:
    // private val pollutants: String = Emissions.values.toList.map(emissions.get(_).getOrElse(0.0).toString).mkString(",")

    // With this implementation:
    private val pollutants: String = Emissions.values.toList
      .flatMap(emType => {
        val value = emissions.get(emType).getOrElse(0.0)
        if (value > 0) Some(s"${emType.toString}:$value")
        else None
      })
      .mkString(";")

    override def toCsv: String = s"$pollutants,$travelTime,$parkingDuration,$observations,$iterations"
  }

  def emissionsSkimOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("EmissionsSkimmer", "skimsEmissions.csv.gz", iterationLevel = true)(
      """
      hour          | Hour of the day
      linkId        | Link ID
      vehicleType   | Type of vehicle
      emissionsProcess | Emissions process (RUNEX, IDLEX, STREX, DIURN, HOTSOAK, RUNLOSS, PMTW, PMBW, PRDUST)
      emissions     | String representation of non-zero emissions in format 'pollutant1:value1;pollutant2:value2'
      travelTimeInSecond  | Average travel time in second
      parkingDuration | Parking duration in seconds
      observations  | Number of events
      iterations    | The current iteration number
      """
    )

  def aggregatedEmissionsSkimOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("EmissionsSkimmer", "skimsEmissions_Aggregated.csv.gz", iterationLevel = true)(
      """
      hour          | Hour of the day
      linkId        | Link ID
      vehicleType   | Type of vehicle
      emissionsProcess | Emissions process (RUNEX, IDLEX, STREX, DIURN, HOTSOAK, RUNLOSS, PMTW, PMBW, PRDUST)
      emissions     | Average (over last n iterations) non-zero emissions in format 'pollutant1:value1;pollutant2:value2'
      travelTimeInSecond  | Average (over last n iterations) travel time
      parkingDuration | Parking (over last n iterations) duration
      observations  | Average (over last n iterations) number of events
      iterations    | Number of iterations
      """
    )
}
