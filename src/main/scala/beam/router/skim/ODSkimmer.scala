package beam.router.skim
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.taz.{H3TAZ, TAZ}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, CAV, DRIVE_TRANSIT, RIDE_HAIL, RIDE_HAIL_POOLED, RIDE_HAIL_TRANSIT, TRANSIT, WALK, WALK_TRANSIT}
import beam.sim.BeamServices
import beam.utils.ProfilingUtils
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.utils.io.IOUtils

import scala.collection.immutable

class ODSkimmer(beamServices: BeamServices, h3taz: H3TAZ) extends AbstractSkimmer(beamServices, h3taz) {

  import ODSkimmer._
  import beamServices._

  val aggregatedSkimsFilePath: String = beamConfig.beam.warmStart.skimsFilePath
  val aggregatedSkimsFileBaseName: String = "odskimsAggregated.csv.gz"
  val CsvLineHeader: String =
    "hour,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,numObservations,energy" + Eol
  val observedSkimsFileBaseName = "odskims"
  val fullSkimsFileBaseName = "odskimsFull"
  val excerptSkimsFileBaseName = "odskimsExcerpt"

  override def handleEvent(event: Event): Unit = ???

  override def writeToDisk(event: IterationEndsEvent): Unit = {
    if (beamConfig.beam.skimmanager.odskimmer.writeObservedSkimsInterval > 0 && event.getIteration % beamConfig.beam.skimmanager.odskimmer.writeObservedSkimsInterval == 0) {
      ProfilingUtils.timed(s"writeObservedSkims on iteration ${event.getIteration}", x => logger.info(x)) {
        writeObservedSkims(event)
      }
    }
    if (beamConfig.beam.skimmanager.odskimmer.writeAllModeSkimsForPeakNonPeakPeriodsInterval > 0 && event.getIteration % beamConfig.beam.skimmanager.odskimmer.writeAllModeSkimsForPeakNonPeakPeriodsInterval == 0) {
      ProfilingUtils.timed(
        s"writeAllModeSkimsForPeakNonPeakPeriods on iteration ${event.getIteration}",
        x => logger.info(x)
      ) {
        writeAllModeSkimsForPeakNonPeakPeriods(event)
      }
    }
    if (beamConfig.beam.skimmanager.odskimmer.writeFullSkimsInterval > 0 && event.getIteration % beamConfig.beam.skimmanager.odskimmer.writeFullSkimsInterval == 0) {
      ProfilingUtils.timed(s"writeFullSkims on iteration ${event.getIteration}", x => logger.info(x)) {
        writeFullSkims(event)
      }
    }
    if (beamConfig.beam.skimmanager.odskimmer.writeAggregatedSkimsInterval > 0 && event.getIteration % beamConfig.beam.skimmanager.odskimmer.writeAggregatedSkimsInterval == 0) {
      ProfilingUtils.timed(s"writeFullSkims on iteration ${event.getIteration}", x => logger.info(x)) {
        writeAggregatedSkims(event)
      }
    }
  }



  // *****
  // Helpers

  private def writeObservedSkims(event: IterationEndsEvent): Unit = {
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      observedSkimsFileBaseName + ".csv.gz"
    )
    val writer = IOUtils.getBufferedWriter(filePath)
    try {
      writer.write(CsvLineHeader + Eol)
      currentSkim.foreach(row => writer.write(row._1.toCsv+ "," + row._2 + Eol))
    } finally {
      writer.close()
    }
  }

  private def writeAggregatedSkims(event: IterationEndsEvent): Unit = {
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      aggregatedSkimsFileBaseName + ".csv.gz"
    )
    val writer = IOUtils.getBufferedWriter(filePath)
    try {
      writer.write(CsvLineHeader + Eol)
      aggregatedSkim.foreach(row => writer.write(row._1.toCsv+ "," + row._2 + Eol))
    } finally {
      writer.close()
    }
  }

  def writeAllModeSkimsForPeakNonPeakPeriods(event: IterationEndsEvent): Unit = {
    val morningPeakHours = (7 to 8).toList
    val afternoonPeakHours = (15 to 16).toList
    val nonPeakHours = (0 to 6).toList ++ (9 to 14).toList ++ (17 to 23).toList
    val modes = BeamMode.allModes
    val fileHeader =
      "period,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,numObservations,energy"
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      excerptSkimsFileBaseName + ".csv.gz"
    )
    val dummyId = Id.create(
      beamScenario.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
      classOf[BeamVehicleType]
    )
    val writer = IOUtils.getBufferedWriter(filePath)
    writer.write(fileHeader)
    writer.write(Eol)

    val weightedSkims = ProfilingUtils.timed("Get weightedSkims for modes", x => logger.info(x)) {
      modes.toParArray.flatMap { mode =>
        beamScenario.tazTreeMap.getTAZs.flatMap { origin =>
          beamScenario.tazTreeMap.getTAZs.flatMap { destination =>
            val am = getExcerptData(
              "AM",
              morningPeakHours,
              origin,
              destination,
              mode,
              dummyId
            )
            val pm = getExcerptData(
              "PM",
              afternoonPeakHours,
              origin,
              destination,
              mode,
              dummyId
            )
            val offPeak = getExcerptData(
              "OffPeak",
              nonPeakHours,
              origin,
              destination,
              mode,
              dummyId
            )
            List(am, pm, offPeak)
          }
        }
      }
    }
    logger.info(s"weightedSkims size: ${weightedSkims.size}")

    weightedSkims.seq.foreach { ws: ExcerptData =>
      writer.write(
        s"${ws.timePeriodString},${ws.mode},${ws.originTazId},${ws.destinationTazId},${ws.weightedTime},${ws.weightedGeneralizedTime},${ws.weightedCost},${ws.weightedGeneralizedCost},${ws.weightedDistance},${ws.sumWeights},${ws.weightedEnergy}\n"
      )
    }
    writer.close()
  }

  def writeFullSkims(event: IterationEndsEvent): Unit = {
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      fullSkimsFileBaseName + ".csv.gz"
    )
    val uniqueModes = currentSkim.map(keyVal => keyVal._1._2).toList.distinct
    val uniqueTimeBins = 0 to 23

    val dummyId = Id.create(
      beamScenario.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
      classOf[BeamVehicleType]
    )

    val writer = IOUtils.getBufferedWriter(filePath)
    writer.write(CsvLineHeader)

    beamScenario.tazTreeMap.getTAZs
      .foreach { origin =>
        beamScenario.tazTreeMap.getTAZs.foreach { destination =>
          uniqueModes.foreach { mode =>
            uniqueTimeBins
              .foreach { timeBin =>
                val theSkim: Skim = getSkimValue(timeBin * 3600, mode, origin.tazId, destination.tazId)
                  .map(_.toSkimExternal)
                  .getOrElse {
                    if (origin.equals(destination)) {
                      val newDestCoord = new Coord(
                        origin.coord.getX,
                        origin.coord.getY + Math.sqrt(origin.areaInSquareMeters) / 2.0
                      )
                      getSkimDefaultValue(
                        mode,
                        origin.coord,
                        newDestCoord,
                        timeBin * 3600,
                        dummyId
                      )
                    } else {
                      getSkimDefaultValue(
                        mode,
                        origin.coord,
                        destination.coord,
                        timeBin * 3600,
                        dummyId
                      )
                    }
                  }

                writer.write(
                  s"$timeBin,$mode,${origin.tazId},${destination.tazId},${theSkim.time},${theSkim.generalizedTime},${theSkim.cost},${theSkim.generalizedTime},${theSkim.distance},${theSkim.count},${theSkim.energy}$Eol"
                )
              }
          }
        }
      }
    writer.close()
  }

  override def fromCsv(line: immutable.Map[String, String]): immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = {
    immutable.Map(
      ODSkimmerKey(
        line("timeBin").toInt,
        BeamMode.fromString("mode").get,
        Id.create(line("idTaz"), classOf[TAZ]),
        Id.create(line("idTaz"), classOf[TAZ]))
        -> ODSkimmerInternal(
        line("travelTimeInS").toDouble,
        line("generalizedTimeInS").toDouble,
        line("generalizedCost").toDouble,
        line("distanceInM").toDouble,
        line("cost").toDouble,
        line("numObservations").toInt,
        Option(line("energy")).map(_.toDouble).getOrElse(0.0))
    )
  }

}


object ODSkimmer extends LazyLogging {
  case class ODSkimmerKey(
                         timeBin: Int,
                         mode: BeamMode,
                         originTaz: Id[TAZ],
                         destinationTaz: Id[TAZ]) extends AbstractSkimmerKey {
    override def toCsv: String = timeBin + "," + mode + "," + originTaz + "," + destinationTaz
  }
  case class ODSkimmerInternal(
                                travelTimeInS: Double,
                                generalizedTimeInS: Double,
                                generalizedCost: Double,
                                distanceInM: Double,
                                cost: Double,
                                numObservations: Int,
                                energy: Double
                         ) extends AbstractSkimmerInternal {
    //NOTE: All times in seconds here
    def toSkimExternal: Skim = Skim(travelTimeInS.toInt, generalizedTimeInS, generalizedCost, distanceInM, cost, numObservations, energy)
    def +(that: AbstractSkimmerInternal): AbstractSkimmerInternal = ODSkimmerInternal(
      this.travelTimeInS + that.asInstanceOf[ODSkimmerInternal].travelTimeInS,
      this.generalizedTimeInS + that.asInstanceOf[ODSkimmerInternal].generalizedTimeInS,
      this.generalizedCost + that.asInstanceOf[ODSkimmerInternal].generalizedCost,
      this.distanceInM + that.asInstanceOf[ODSkimmerInternal].distanceInM,
      this.cost + that.asInstanceOf[ODSkimmerInternal].cost,
      this.numObservations + that.asInstanceOf[ODSkimmerInternal].numObservations,
      this.energy + that.asInstanceOf[ODSkimmerInternal].energy)
    def /(thatInt: Int): AbstractSkimmerInternal = ODSkimmerInternal(
      this.travelTimeInS / thatInt,
      this.generalizedTimeInS / thatInt,
      this.generalizedCost / thatInt,
      this.distanceInM / thatInt,
      this.cost / thatInt,
      this.numObservations / thatInt,
      this.energy / thatInt)
    def *(thatInt: Int): AbstractSkimmerInternal = ODSkimmerInternal(
      this.travelTimeInS * thatInt,
      this.generalizedTimeInS * thatInt,
      this.generalizedCost * thatInt,
      this.distanceInM * thatInt,
      this.cost * thatInt,
      this.numObservations * thatInt,
      this.energy * thatInt)
    override def toCsv: String = travelTimeInS + "," + generalizedTimeInS + "," + generalizedCost + "," + distanceInM + "," + cost + "," + numObservations + "," + energy
  }

  case class Skim(
                   time: Int,
                   generalizedTime: Double,
                   generalizedCost: Double,
                   distance: Double,
                   cost: Double,
                   count: Int,
                   energy: Double
                 )

  case class ExcerptData(
                          timePeriodString: String,
                          mode: BeamMode,
                          originTazId: Id[TAZ],
                          destinationTazId: Id[TAZ],
                          weightedTime: Double,
                          weightedGeneralizedTime: Double,
                          weightedCost: Double,
                          weightedGeneralizedCost: Double,
                          weightedDistance: Double,
                          sumWeights: Double,
                          weightedEnergy: Double
                        )

  // 22.2 mph (9.924288 meter per second), is the average speed in cities
  //TODO better estimate can be drawn from city size
  // source: https://www.mitpressjournals.org/doi/abs/10.1162/rest_a_00744
  private val carSpeedMeterPerSec: Double = 9.924288
  // 12.1 mph (5.409184 meter per second), is average bus speed
  // source: https://www.apta.com/resources/statistics/Documents/FactBook/2017-APTA-Fact-Book.pdf
  // assuming for now that it includes the headway
  private val transitSpeedMeterPerSec: Double = 5.409184
  private val bicycleSpeedMeterPerSec: Double = 3
  // 3.1 mph -> 1.38 meter per second
  private val walkSpeedMeterPerSec: Double = 1.38
  // 940.6 Traffic Signal Spacing, Minor is 1,320 ft => 402.336 meters
  private val trafficSignalSpacing: Double = 402.336
  // average waiting time at an intersection is 17.25 seconds
  // source: https://pumas.nasa.gov/files/01_06_00_1.pdf
  private val waitingTimeAtAnIntersection: Double = 17.25

  val speedMeterPerSec: Map[BeamMode, Double] = Map(
    CAV               -> carSpeedMeterPerSec,
    CAR               -> carSpeedMeterPerSec,
    WALK              -> walkSpeedMeterPerSec,
    BIKE              -> bicycleSpeedMeterPerSec,
    WALK_TRANSIT      -> transitSpeedMeterPerSec,
    DRIVE_TRANSIT     -> transitSpeedMeterPerSec,
    RIDE_HAIL         -> carSpeedMeterPerSec,
    RIDE_HAIL_POOLED  -> carSpeedMeterPerSec,
    RIDE_HAIL_TRANSIT -> transitSpeedMeterPerSec,
    TRANSIT           -> transitSpeedMeterPerSec
  )
}
