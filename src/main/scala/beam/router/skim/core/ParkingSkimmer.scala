package beam.router.skim.core

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.Skims
import beam.router.skim.readonly.ParkingSkims
import beam.sim.config.BeamConfig
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import enumeratum.EnumEntry.LowerCamelcase
import enumeratum.{Enum, EnumEntry}
import org.matsim.api.core.v01.Id
import org.matsim.core.controler.MatsimServices

import scala.collection.immutable

/**
  * @author Dmitry Openkov
  */
class ParkingSkimmer @Inject() (
  matsimServices: MatsimServices,
  beamConfig: BeamConfig
) extends AbstractSkimmer(beamConfig, matsimServices.getControlerIO) {
  import ParkingSkimmer._
  override protected[skim] val readOnlySkim = new ParkingSkims
  override protected val skimFileBaseName: String = ParkingSkimmer.fileBaseName

  override protected val skimFileHeader =
    "tazId,hour,chargerType,walkAccessDistanceInM,parkingCostPerHour,completedTrips,iterations"
  override protected val skimName: String = ParkingSkimmer.name
  override protected val skimType: Skims.SkimType.Value = Skims.SkimType.PARKING_SKIMMER

  override protected def fromCsv(
    line: collection.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      ParkingSkimmerKey(
        tazId = line("tazId").createId,
        hour = line("hour").toInt,
        chargerType = ChargerType.withNameInsensitive(line("chargerType"))
      ),
      ParkingSkimmerInternal(
        walkAccessDistanceInM = line("walkAccessDistanceInM").toDouble,
        parkingCostPerHour = line("parkingCostPerHour").toDouble,
        completedTrips = line("completedTrips").toInt,
        iterations = line("iterations").toInt
      )
    )
  }

  override protected def aggregateOverIterations(
    prevIteration: Option[AbstractSkimmerInternal],
    currIteration: Option[AbstractSkimmerInternal]
  ): AbstractSkimmerInternal =
    AbstractSkimmer.aggregateOverIterations[ParkingSkimmerInternal](prevIteration, currIteration) { agg =>
      ParkingSkimmerInternal(
        walkAccessDistanceInM = agg.aggregate(_.walkAccessDistanceInM),
        parkingCostPerHour = agg.aggregate(_.parkingCostPerHour),
        completedTrips = agg.aggregate(_.completedTrips),
        iterations = agg.aggregateObservations
      )
    }

  override protected def aggregateWithinIteration(
    prevObservation: Option[AbstractSkimmerInternal],
    currObservation: AbstractSkimmerInternal
  ): AbstractSkimmerInternal =
    AbstractSkimmer.aggregateWithinIteration[ParkingSkimmerInternal](prevObservation, currObservation) { agg =>
      ParkingSkimmerInternal(
        walkAccessDistanceInM = agg.aggregate(_.walkAccessDistanceInM),
        parkingCostPerHour = agg.aggregate(_.parkingCostPerHour),
        completedTrips = agg.aggregateObservations
      )
    }
}

object ParkingSkimmer extends LazyLogging {
  val name = "parking-skimmer"
  val fileBaseName = "skimsParking"

  case class ParkingSkimmerKey(tazId: Id[TAZ], hour: Int, chargerType: ChargerType) extends AbstractSkimmerKey {
    override def toCsv: String = productIterator.mkString(",")
  }

  case class ParkingSkimmerInternal(
    walkAccessDistanceInM: Double,
    parkingCostPerHour: Double,
    completedTrips: Int = 1,
    iterations: Int = 1
  ) extends AbstractSkimmerInternal {
    override def toCsv: String = AbstractSkimmer.toCsv(productIterator)
  }

  sealed abstract class ChargerType extends EnumEntry

  object ChargerType extends Enum[ChargerType] {

    val values: immutable.IndexedSeq[ChargerType] = findValues

    case object NoCharger extends ChargerType with LowerCamelcase
    case object ACSlowCharger extends ChargerType with LowerCamelcase
    case object DCFastCharger extends ChargerType with LowerCamelcase

  }
}
