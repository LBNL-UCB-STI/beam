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
    val emissionHeaders = Emissions.values.map(formatName).mkString(",")
    s"hour,linkId,tazId,vehicleTypeId,emissionsProcess,$emissionHeaders,travelTimeInSecond,energyInJoule,parkingDurationInSecond,observations,iterations"
  }

  override def fromCsv(
    line: scala.collection.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      EmissionsSkimmerKey(
        line("linkId"),
        line("vehicleTypeId"),
        line("hour").toInt,
        line("tazId"),
        EmissionsProfile.withName(line("emissionsProcess"))
      ),
      EmissionsSkimmerInternal(
        Emissions(
          Emissions.values.flatMap { emissionType =>
            line.get(formatName(emissionType)).flatMap { value =>
              try {
                Some(emissionType -> value.toDouble)
              } catch {
                case _: NumberFormatException => None
              }
            }
          }.toMap
        ),
        line("travelTimeInSecond").toDouble,
        line("energyInJoule").toDouble,
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
      energyConsumed =
        (prevSkim.energyConsumed * prevSkim.iterations + currSkim.energyConsumed * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
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
      energyConsumed =
        (prevSkim.energyConsumed * prevSkim.observations + currSkim.energyConsumed * currSkim.observations) / (prevSkim.observations + currSkim.observations),
      parkingDuration =
        (prevSkim.parkingDuration * prevSkim.observations + currSkim.parkingDuration * currSkim.observations) / (prevSkim.observations + currSkim.observations),
      observations = prevSkim.observations + currSkim.observations,
      iterations = prevSkim.iterations
    )
  }
}

object EmissionsSkimmer extends LazyLogging {

  case class EmissionsSkimmerKey(
    linkId: String,
    vehicleTypeId: String,
    hour: Int,
    tazId: String,
    emissionsProcess: EmissionsProfile.EmissionsProcess
  ) extends AbstractSkimmerKey {
    override def toCsv: String = s"$hour,$linkId,$tazId,$vehicleTypeId,${emissionsProcess.toString}"
  }

  case class EmissionsSkimmerInternal(
    emissions: Emissions,
    travelTime: Double,
    energyConsumed: Double,
    parkingDuration: Double,
    observations: Int = 0,
    iterations: Int = 0
  ) extends AbstractSkimmerInternal {
    private val pollutants: String = Emissions.values.toList.map(emissions.get(_).getOrElse(0.0).toString).mkString(",")
    override def toCsv: String = s"$pollutants,$travelTime,$energyConsumed,$parkingDuration,$observations,$iterations"
  }

  def emissionsSkimOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("EmissionsSkimmer", "skimsEmissions.csv.gz", iterationLevel = true)(
      s"""
        hour          | Hour of the day
        linkId        | Link ID
        tazId         | TAZ ID
        vehicleType   | Type of vehicle
        emissionsProcess | Emissions process (RUNEX, IDLEX, STREX, DIURN, HOTSOAK, RUNLOSS, PMTW, PMBW)
        ${CH4.toString}           | Methane emissions rate
        ${CO.toString}            | Carbon monoxide emissions rate
        ${CO2.toString}           | Carbon dioxide emissions rate
        ${HC.toString}            | Hydrocarbon emissions rate
        ${NH3.toString}           | Ammonia emissions rate
        ${NOx.toString}           | Nitrogen oxides emissions rate
        ${PM.toString}            | Particulate matter emissions rate
        ${PM10.toString}          | Particulate matter (10 micrometers) emissions rate
        ${PM2_5.toString}         | Particulate matter (2.5 micrometers) emissions rate
        ${ROG.toString}           | Reactive organic gases emissions rate
        ${SOx.toString}           | Sulfur oxides emissions rate
        ${TOG.toString}           | Total organic gases emissions rate
        travelTimeInSecond  | Average travel time in second
        energyConsumption | Energy consumption in joule
        parkingDuration | Parking duration in seconds
        observations  | Number of events
        iterations    | The current iteration number
        """
    )

  def aggregatedEmissionsSkimOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("EmissionsSkimmer", "skimsEmissions_Aggregated.csv.gz", iterationLevel = true)(
      s"""
        hour          | Hour of the day
        linkId        | Link ID
        tazId         | TAZ ID
        vehicleType   | Type of vehicle
        emissionsProcess | Emissions process (RUNEX, IDLEX, STREX, DIURN, HOTSOAK, RUNLOSS, PMTW, PMBW)
        ${CH4.toString}           | Average (over last n iterations) methane emissions rate
        ${CO.toString}            | Average (over last n iterations) carbon monoxide emissions rate
        ${CO2.toString}           | Average (over last n iterations) carbon dioxide emissions rate
        ${HC.toString}            | Average (over last n iterations) hydrocarbon emissions rate
        ${NH3.toString}           | Average (over last n iterations) ammonia emissions rate
        ${NOx.toString}           | Average (over last n iterations) nitrogen oxides emissions rate
        ${PM.toString}            | Average (over last n iterations) particulate matter emissions rate
        ${PM10.toString}          | Average (over last n iterations) particulate matter (10 micrometers) emissions rate
        ${PM2_5.toString}         | Average (over last n iterations) particulate matter (2.5 micrometers) emissions rate
        ${ROG.toString}           | Average (over last n iterations) reactive organic gases emissions rate
        ${SOx.toString}           | Average (over last n iterations) sulfur oxides emissions rate
        ${TOG.toString}           | Average (over last n iterations) total organic gases emissions rate
        travelTimeInSecond  | Average (over last n iterations) travel time
        energyConsumption | Average (over last n iterations) energy consumption
        parkingDuration | Parking (over last n iterations) duration
        observations  | Average (over last n iterations) number of events
        iterations    | Number of iterations
        """
    )
}
