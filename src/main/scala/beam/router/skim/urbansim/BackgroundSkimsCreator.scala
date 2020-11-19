package beam.router.skim.urbansim

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.pattern._
import akka.util.Timeout
import beam.agentsim.infrastructure.geozone.H3Wrapper
import beam.router.Modes.BeamMode
import beam.router.r5.{R5Parameters, R5Wrapper, WorkerParameters}
import beam.router.skim.urbansim.MasterActor.Response
import beam.router.skim.{GeoUnit, ODSkimmer}
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.ProfilingUtils
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.router.util.TravelTime

import scala.concurrent.Future

class BackgroundSkimsCreator(
  val beamServices: BeamServices,
  val beamScenario: BeamScenario,
  val geoClustering: GeoClustering,
  val odSkimmer: ODSkimmer,
  val travelTime: TravelTime,
  val beamModes: Array[BeamMode],
  val withTransit: Boolean
)(implicit actorSystem: ActorSystem) {

  private implicit val timeout: Timeout = Timeout(6, TimeUnit.HOURS)

  private val r5Wrapper: R5Wrapper = new R5Wrapper(
    R5Parameters(
      beamServices.beamConfig,
      beamScenario.transportNetwork,
      beamScenario.vehicleTypes,
      beamScenario.fuelTypePrices,
      beamScenario.ptFares,
      beamServices.geo,
      beamScenario.dates,
      beamServices.networkHelper,
      beamServices.fareCalculator,
      beamServices.tollCalculator
    ),
    travelTime,
    0.0
  )

  private val masterActorRef: ActorRef = {
    val actorName = s"Modes-${beamModes.mkString("_")}-with-transit-${withTransit}-${UUID.randomUUID()}"
    val masterProps = MasterActor.props(
      geoClustering,
      odSkimmer,
      // Array(BeamMode.WALK, BeamMode.WALK_TRANSIT, BeamMode.BIKE)
      new ODR5Requester(
        vehicleTypes = beamScenario.vehicleTypes,
        r5Wrapper = r5Wrapper,
        scenario = beamServices.matsimServices.getScenario,
        geoUtils = beamServices.geo,
        beamModes = beamModes,
        beamConfig = beamServices.beamConfig,
        modeChoiceCalculatorFactory = beamServices.modeChoiceCalculatorFactory,
        withTransit = withTransit
      )
    )
    actorSystem.actorOf(masterProps, actorName)
  }

  def start(): Unit = {
    masterActorRef ! MasterActor.Request.Start
  }

  def stop(): Unit = {
    masterActorRef ! MasterActor.Request.Stop
  }

  def reduceParallelismTo(parallelism: Int): Unit = {
    masterActorRef ! MasterActor.Request.ReduceParallelismTo(parallelism)
  }

  def increaseParallelismTo(parallelism: Int): Unit = {
    masterActorRef ! MasterActor.Request.IncreaseParallelismTo(parallelism)
  }

  def getResult: Future[Response.PopulatedSkimmer] = {
    masterActorRef.ask(MasterActor.Request.WaitToFinish).mapTo[MasterActor.Response.PopulatedSkimmer]
  }

}

object BackgroundSkimsCreator {

  def createODSkimmer(beamServices: BeamServices, clustering: GeoClustering): ODSkimmer = {
    clustering match {
      case tazClustering: TAZClustering => createTAZOdSkimmer(beamServices, tazClustering)
      case h3Clustering: H3Clustering   => createH3ODSkimmer(beamServices, h3Clustering)
    }
  }

  private val additionalSkimFileNamePart = ".UrbanSim"

  private def createTAZOdSkimmer(beamServices: BeamServices, tazClustering: TAZClustering): ODSkimmer =
    new ODSkimmer(beamServices.matsimServices, beamServices.beamScenario, beamServices.beamConfig) {
      override def writeToDisk(event: IterationEndsEvent): Unit = {
        ProfilingUtils.timed(s"writeFullSkims on iteration ${event.getIteration}", v => logger.info(v)) {
          val filePath = event.getServices.getControlerIO.getIterationFilename(
            event.getServices.getIterationNumber,
            skimFileBaseName + additionalSkimFileNamePart + ".Full.csv.gz"
          )
          val hour = beamServices.beamConfig.beam.urbansim.backgroundODSkimsCreator.peakHour.toInt
          val uniqueTimeBins: Seq[Int] = hour to hour
          val origins = tazClustering.tazTreeMap.getTAZs
            .map(taz => GeoUnit.TAZ(taz.tazId.toString, taz.coord, taz.areaInSquareMeters))
            .toSeq
          writeFullSkims(origins, origins, uniqueTimeBins, filePath)
          logger.info(s"Written UrbanSim peak skims for hour $hour to $filePath")
        }
      }
    }

  private def createH3ODSkimmer(beamServices: BeamServices, h3Clustering: H3Clustering): ODSkimmer =
    new ODSkimmer(beamServices.matsimServices, beamServices.beamScenario, beamServices.beamConfig) {
      override def writeToDisk(event: IterationEndsEvent): Unit = {
        ProfilingUtils.timed(s"writeFullSkims on iteration ${event.getIteration}", v => logger.info(v)) {
          val filePath = event.getServices.getControlerIO.getIterationFilename(
            event.getServices.getIterationNumber,
            skimFileBaseName + additionalSkimFileNamePart + ".Full.csv.gz"
          )

          val hour = beamServices.beamConfig.beam.urbansim.backgroundODSkimsCreator.peakHour.toInt
          val uniqueTimeBins: Seq[Int] = hour to hour
          val origins: Seq[GeoUnit.H3] = h3Clustering.h3Indexes.map { h3Index =>
            val wgsCenter = H3Wrapper.wgsCoordinate(h3Index.index).coord
            val utmCenter = beamServices.geo.wgs2Utm(wgsCenter)
            val areaInSquareMeters = H3Wrapper.hexAreaM2(h3Index.index.resolution)
            GeoUnit.H3(h3Index.index.value, utmCenter, areaInSquareMeters)
          }

          writeFullSkims(origins, origins, uniqueTimeBins, filePath)
          logger.info(s"Written UrbanSim peak skims for hour $hour to $filePath")
        }
      }
    }

}
