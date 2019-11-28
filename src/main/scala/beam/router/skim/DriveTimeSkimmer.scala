package beam.router.skim

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.CAR
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import org.jfree.data.statistics.HistogramDataset
import org.matsim.api.core.v01.Id
import org.matsim.core.controler.events.IterationEndsEvent

import scala.collection.mutable

class DriveTimeSkimmer(beamServices: BeamServices, config: BeamConfig.Beam.Router.Skim)
    extends AbstractSkimmer(beamServices, config) {
  import SkimsUtils._
  import DriveTimeSkimmer._
  import beamServices._

  val maxDistanceFromBeamTaz: Double = 500.0 // 500 meters
  val uniqueModes: List[BeamMode.CAR.type] = List(CAR)
  val uniqueTimeBins: Range.Inclusive = 0 to 23

  override protected[skim] lazy val readOnlySkim: AbstractSkimmerReadOnly = DriveTimeSkims(beamServices)
  override protected val skimFileBaseName: String = config.drive_time_skimmer.fileBaseName
  override protected val skimFileHeader: String =
    "fromTAZId,toTAZId,hour,timeSimulated,timeObserved,counts,numIteration"
  override protected val skimName: String = config.drive_time_skimmer.name
  private val chartName: String = "scatterplot_simulation_vs_reference.png"
  private val histogramName: String = "simulation_vs_reference_histogram.png"
  private val histogramBinSize: Int = 200
  private lazy val observedTravelTimes = buildObservedODTravelTime(beamServices, maxDistanceFromBeamTaz)

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    var series = new mutable.ListBuffer[(Int, Double, Double)]()
    val categoryDataset = new HistogramDataset()
    var deltasOfObservedSimulatedTimes = new mutable.ListBuffer[Double]
    if (observedTravelTimes.nonEmpty) {
      beamScenario.tazTreeMap.getTAZs
        .foreach { origin =>
          beamScenario.tazTreeMap.getTAZs.foreach { destination =>
            uniqueModes.foreach { _ =>
              uniqueTimeBins.foreach { timeBin =>
                val key = PathCache(origin.tazId, destination.tazId, timeBin)
                observedTravelTimes.get(key).foreach { timeObserved =>
                  val theSkimKey = DriveTimeSkimmerKey(origin.tazId, destination.tazId, timeBin * 3600)
                  currentSkim.get(theSkimKey).map(_.asInstanceOf[DriveTimeSkimmerInternal]).foreach { theSkimInternal =>
                    series += ((theSkimInternal.numObservations, theSkimInternal.timeSimulated, timeObserved))
                    for (_ <- 1 to theSkimInternal.numObservations)
                      deltasOfObservedSimulatedTimes += theSkimInternal.timeSimulated - timeObserved
                    currentSkim.update(theSkimKey, theSkimInternal.copy(timeObserved = timeObserved))
                  }
                }
              }
            }
          }
        }
      categoryDataset.addSeries("Simulated-Observed", deltasOfObservedSimulatedTimes.toArray, histogramBinSize)
      val chartPath =
        event.getServices.getControlerIO.getIterationFilename(event.getServices.getIterationNumber, chartName)
      generateChart(series, chartPath)
      val histogramPath =
        event.getServices.getControlerIO.getIterationFilename(event.getServices.getIterationNumber, histogramName)
      generateHistogram(categoryDataset, histogramPath)
    } else {
      logger.warn(s"the skimmer $skimName does not have access to the observed travel time for calibration")
    }

    super.notifyIterationEnds(event)
  }

  override protected def fromCsv(line: Map[String, String]): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      DriveTimeSkimmerKey(
        fromTAZId = Id.create(line("fromTAZId"), classOf[TAZ]),
        toTAZId = Id.create(line("toTAZId"), classOf[TAZ]),
        hour = line("hour").toInt
      ),
      DriveTimeSkimmerInternal(
        timeSimulated = line("timeSimulated").toDouble,
        timeObserved = line("timeObserved").toDouble,
        numObservations = line("counts").toInt,
        numIteration = line("numIteration").toInt
      )
    )
  }

  override protected def aggregateOverIterations(
    prevIteration: Option[AbstractSkimmerInternal],
    currIteration: Option[AbstractSkimmerInternal]
  ): AbstractSkimmerInternal = {
    val prevSkim = prevIteration
      .map(_.asInstanceOf[DriveTimeSkimmerInternal])
      .getOrElse(DriveTimeSkimmerInternal(0, 0, numObservations = 0, numIteration = 0)) // no skim means no observation
    val currSkim = currIteration
      .map(_.asInstanceOf[DriveTimeSkimmerInternal])
      .getOrElse(DriveTimeSkimmerInternal(0, 0, numObservations = 0, numIteration = 1)) // no current skim means 0 observation
    DriveTimeSkimmerInternal(
      timeSimulated = (prevSkim.timeSimulated * prevSkim.numIteration + currSkim.timeSimulated * currSkim.numIteration) / (prevSkim.numIteration + currSkim.numIteration),
      timeObserved = if (currSkim.timeObserved != 0) currSkim.timeObserved else prevSkim.timeObserved,
      numObservations = (prevSkim.numObservations * prevSkim.numIteration + currSkim.numObservations * currSkim.numIteration) / (prevSkim.numIteration + currSkim.numIteration),
      numIteration = prevSkim.numIteration + currSkim.numIteration
    )
  }

  protected def aggregateWithinAnIteration(
    prevObservation: Option[AbstractSkimmerInternal],
    currObservation: AbstractSkimmerInternal
  ): AbstractSkimmerInternal = {
    val prevSkim = prevObservation
      .map(_.asInstanceOf[DriveTimeSkimmerInternal])
      .getOrElse(DriveTimeSkimmerInternal(0, 0, numObservations = 0, numIteration = 0))
    val currSkim = currObservation.asInstanceOf[DriveTimeSkimmerInternal]
    DriveTimeSkimmerInternal(
      timeSimulated = (prevSkim.timeSimulated * prevSkim.numObservations + currSkim.timeSimulated * currSkim.numObservations) / (prevSkim.numObservations + currSkim.numObservations),
      timeObserved = if (currSkim.timeObserved != 0) currSkim.timeObserved else prevSkim.timeObserved,
      numObservations = prevSkim.numObservations + currSkim.numObservations,
      numIteration = beamServices.matsimServices.getIterationNumber + 1
    )
  }
}

object DriveTimeSkimmer extends LazyLogging {

  case class DriveTimeSkimmerKey(fromTAZId: Id[TAZ], toTAZId: Id[TAZ], hour: Int) extends AbstractSkimmerKey {
    override def toCsv: String = fromTAZId + "," + toTAZId + "," + hour
  }

  case class DriveTimeSkimmerInternal(
    timeSimulated: Double,
    timeObserved: Double,
    numObservations: Int = 1,
    numIteration: Int = 0
  ) extends AbstractSkimmerInternal {
    override def toCsv: String = timeSimulated + "," + timeObserved + "," + numObservations + "," + numIteration
  }

}
