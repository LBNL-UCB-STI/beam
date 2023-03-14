package beam.router.skim.core

import beam.router.skim.{readonly, Skims}
import beam.sim.config.BeamConfig
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.controler.MatsimServices

class TAZSkimmer @Inject() (matsimServices: MatsimServices, beamConfig: BeamConfig)
    extends AbstractSkimmer(beamConfig, matsimServices.getControlerIO) {
  import TAZSkimmer._
  private val config: BeamConfig.Beam.Router.Skim = beamConfig.beam.router.skim

  override lazy val readOnlySkim: AbstractSkimmerReadOnly = new readonly.TAZSkims()

  override protected val skimName: String = config.taz_skimmer.name
  override protected val skimType: Skims.SkimType.Value = Skims.SkimType.TAZ_SKIMMER
  override protected val skimFileBaseName: String = config.taz_skimmer.fileBaseName

  override protected val skimFileHeader: String = "time,geoId,actor,key,value,completedTrips,iterations"

  override def fromCsv(
    line: scala.collection.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      TAZSkimmerKey(
        line("time").toInt,
        line("geoId"),
        line("actor"),
        line("key")
      ),
      TAZSkimmerInternal(
        line("value").toDouble,
        line("completedTrips").toInt,
        line("iterations").toInt
      )
    )
  }

  override protected def aggregateOverIterations(
    prevIteration: Option[AbstractSkimmerInternal],
    currIteration: Option[AbstractSkimmerInternal]
  ): AbstractSkimmerInternal = {
    val prevSkim = prevIteration
      .map(_.asInstanceOf[TAZSkimmerInternal])
      .getOrElse(TAZSkimmerInternal(0)) // no skim means no observation
    val currSkim = currIteration
      .map(_.asInstanceOf[TAZSkimmerInternal])
      .getOrElse(
        TAZSkimmerInternal(0, iterations = matsimServices.getIterationNumber + 1)
      ) // no current skim means 0 observation
    TAZSkimmerInternal(
      value =
        (prevSkim.value * prevSkim.iterations + currSkim.value * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
      completedTrips =
        (prevSkim.completedTrips * prevSkim.iterations + currSkim.completedTrips * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
      iterations = prevSkim.iterations + currSkim.iterations
    )
  }

  override protected def aggregateWithinIteration(
    prevObservation: Option[AbstractSkimmerInternal],
    currObservation: AbstractSkimmerInternal
  ): AbstractSkimmerInternal = {
    val prevSkim = prevObservation
      .map(_.asInstanceOf[TAZSkimmerInternal])
      .getOrElse(TAZSkimmerInternal(0, iterations = matsimServices.getIterationNumber + 1))
    val currSkim = currObservation.asInstanceOf[TAZSkimmerInternal]
    TAZSkimmerInternal(
      value =
        (prevSkim.value * prevSkim.completedTrips + currSkim.value * currSkim.completedTrips) / (prevSkim.completedTrips + currSkim.completedTrips),
      completedTrips = prevSkim.completedTrips + currSkim.completedTrips,
      iterations = prevSkim.iterations
    )
  }
}

object TAZSkimmer extends LazyLogging {

  case class TAZSkimmerKey(time: Int, geoId: String, actor: String, key: String) extends AbstractSkimmerKey {
    override def toCsv: String = time + "," + geoId + "," + actor + "," + key
  }

  case class TAZSkimmerInternal(value: Double, completedTrips: Int = 0, iterations: Int = 0)
      extends AbstractSkimmerInternal {
    override def toCsv: String = value + "," + completedTrips + "," + iterations
  }
}
