package beam.router.skim

import java.math.RoundingMode

import beam.router.skim.TransitCrowdingSkimmer.{TransitCrowdingSkimmerInternal, TransitCrowdingSkimmerKey}
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import com.google.common.math.IntMath
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

/**
  *
  * @author Dmitry Openkov
  */
class TransitCrowdingSkimmer(beamServices: BeamServices, config: BeamConfig.Beam.Router.Skim)
    extends AbstractSkimmer(beamServices, config) {
  override protected[skim] val readOnlySkim = new TransitCrowdingSkims()
  override protected val skimFileBaseName = config.transit_crowding_skimmer.fileBaseName
  override protected val skimFileHeader = "vehicleId,fromStopIdx,numberOfPassengers,capacity,observations,iterations"
  override protected val skimName = config.transit_crowding_skimmer.name

  override protected def fromCsv(
    line: collection.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      TransitCrowdingSkimmerKey(
        vehicleId = Id.createVehicleId(line("vehicleId")),
        fromStopIdx = line("fromStopIdx").toInt
      ),
      TransitCrowdingSkimmerInternal(
        numberOfPassengers = line("numberOfPassengers").toInt,
        capacity = line("capacity").toInt,
        iterations = line("iterations").toInt
      )
    )
  }

  override protected def aggregateOverIterations(
    prevIteration: Option[AbstractSkimmerInternal],
    currIteration: Option[AbstractSkimmerInternal]
  ): AbstractSkimmerInternal = {
    val prevSkim = prevIteration.map(_.asInstanceOf[TransitCrowdingSkimmerInternal])
    val currSkim = currIteration.map(_.asInstanceOf[TransitCrowdingSkimmerInternal])
    (prevSkim, currSkim) match {
      case (Some(x), None) => x
      case (None, Some(x)) => x
      case (None, None)    => throw new IllegalArgumentException("Cannot aggregate nothing")
      case (Some(prev), Some(current)) =>
        TransitCrowdingSkimmerInternal(
          numberOfPassengers = IntMath.divide(
            prev.numberOfPassengers * prev.iterations + current.numberOfPassengers * current.iterations,
            prev.iterations + current.iterations,
            RoundingMode.HALF_UP
          ),
          capacity = IntMath.divide(
            prev.capacity * prev.iterations + current.capacity * current.iterations,
            prev.iterations + current.iterations,
            RoundingMode.HALF_UP
          ),
          iterations = prev.iterations + current.iterations
        )
    }
  }

  override protected def aggregateWithinIteration(
    prevObservation: Option[AbstractSkimmerInternal],
    currObservation: AbstractSkimmerInternal
  ): AbstractSkimmerInternal = currObservation
}

object TransitCrowdingSkimmer extends LazyLogging {

  case class TransitCrowdingSkimmerKey(vehicleId: Id[Vehicle], fromStopIdx: Int) extends AbstractSkimmerKey {
    override def toCsv: String = vehicleId + "," + fromStopIdx
  }

  case class TransitCrowdingSkimmerInternal(
    numberOfPassengers: Int,
    capacity: Int,
    iterations: Int = 1,
  ) extends AbstractSkimmerInternal {
    override def toCsv: String = numberOfPassengers + "," + capacity + "," + observations + "," + iterations

    //vehicle id, fromStopIdx are unique within an iteration, so they can be observed only once
    override val observations = 1
  }

}
