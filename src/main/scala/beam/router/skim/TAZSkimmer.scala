package beam.router.skim
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id

import scala.collection.immutable

class TAZSkimmer(beamServices: BeamServices, config: BeamConfig.Beam.Router.Skim)
    extends AbstractSkimmer(beamServices, config) {
  import TAZSkimmer._

  override lazy val readOnlySkim: AbstractSkimmerReadOnly = TAZSkims(beamServices)

  override protected val skimName: String = config.taz_skimmer.name
  override protected val skimFileBaseName: String = config.taz_skimmer.fileBaseName
  override protected val skimFileHeader: String =
    "time,taz,hex,actor,key,value,observations,iterations"

  override def fromCsv(
    line: immutable.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      TAZSkimmerKey(
        line("time").toInt,
        Id.create(line("taz"), classOf[TAZ]),
        line("hex"),
        line("actor"),
        line("key")
      ),
      TAZSkimmerInternal(
        line("value").toDouble,
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
      .map(_.asInstanceOf[TAZSkimmerInternal])
      .getOrElse(TAZSkimmerInternal(0)) // no skim means no observation
    val currSkim = currIteration
      .map(_.asInstanceOf[TAZSkimmerInternal])
      .getOrElse(
        TAZSkimmerInternal(0, observations = 0, iterations = beamServices.matsimServices.getIterationNumber + 1)
      ) // no current skim means 0 observation
    TAZSkimmerInternal(
      value = (prevSkim.value * prevSkim.iterations + currSkim.value * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
      observations = (prevSkim.observations * prevSkim.iterations + currSkim.observations * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
      iterations = prevSkim.iterations + currSkim.iterations
    )
  }

  override protected def aggregateWithinIteration(
    prevObservation: Option[AbstractSkimmerInternal],
    currObservation: AbstractSkimmerInternal
  ): AbstractSkimmerInternal = {
    val prevSkim = prevObservation
      .map(_.asInstanceOf[TAZSkimmerInternal])
      .getOrElse(
        TAZSkimmerInternal(0, observations = 0, iterations = beamServices.matsimServices.getIterationNumber + 1)
      )
    val currSkim = currObservation.asInstanceOf[TAZSkimmerInternal]
    TAZSkimmerInternal(
      value = (prevSkim.value * prevSkim.observations + currSkim.value * currSkim.observations) / (prevSkim.observations + currSkim.observations),
      observations = prevSkim.observations + currSkim.observations,
      iterations = prevSkim.iterations
    )
  }
}

object TAZSkimmer extends LazyLogging {
  case class TAZSkimmerKey(
    time: Int,
    taz: Id[TAZ],
    hex: String,
    actor: String,
    key: String
  ) extends AbstractSkimmerKey {
    override def toCsv: String = time + "," + taz + "," + hex + "," + actor + "," + key
  }
  case class TAZSkimmerInternal(value: Double, observations: Int = 0, iterations: Int = 0)
      extends AbstractSkimmerInternal {
    override def toCsv: String = value + "," + observations + "," + iterations
  }
}
