package beam.router.skim.urbansim

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern._
import akka.util.Timeout
import beam.agentsim.infrastructure.geozone.H3Wrapper
import beam.router.Modes.BeamMode
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.router.skim.urbansim.MasterActor.Response
import beam.router.skim._
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.ProfilingUtils
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.router.util.TravelTime

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.Future

class BackgroundSkimsCreator(
  val beamServices: BeamServices,
  val beamScenario: BeamScenario,
  val geoClustering: GeoClustering,
  val abstractSkimmer: AbstractSkimmer,
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
    val actorName = s"Modes-${beamModes.mkString("_")}-with-transit-$withTransit-${UUID.randomUUID()}"
    val backgroundODSkimsCreator = beamServices.beamConfig.beam.urbansim.backgroundODSkimsCreator
    val skimmerEventFactory: AbstractSkimmerEventFactory = backgroundODSkimsCreator.skimsKind match {
      case "od"          => new ODSkimmerEventFactory
      case "activitySim" => new ActivitySimSkimmerEventFactory(beamServices.beamConfig)
      case kind @ _      => throw new IllegalArgumentException(s"Unexpected skims kind: $kind")
    }

    val masterProps = MasterActor.props(
      geoClustering,
      abstractSkimmer,
      // Array(BeamMode.WALK, BeamMode.WALK_TRANSIT, BeamMode.BIKE)
      new ODR5Requester(
        vehicleTypes = beamScenario.vehicleTypes,
        r5Wrapper = r5Wrapper,
        scenario = beamServices.matsimServices.getScenario,
        geoUtils = beamServices.geo,
        beamModes = beamModes,
        beamConfig = beamServices.beamConfig,
        modeChoiceCalculatorFactory = beamServices.modeChoiceCalculatorFactory,
        withTransit = withTransit,
        requestTime = (backgroundODSkimsCreator.peakHour * 3600).toInt,
        skimmerEventFactory
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

  def createSkimmer(beamServices: BeamServices, clustering: GeoClustering): AbstractSkimmer = {
    (clustering, beamServices.beamConfig.beam.urbansim.backgroundODSkimsCreator.skimsKind) match {
      case (tazClustering: TAZClustering, "od")          => createTAZOdSkimmer(beamServices, tazClustering)
      case (h3Clustering: H3Clustering, "od")            => createH3ODSkimmer(beamServices, h3Clustering)
      case (tazClustering: TAZClustering, "activitySim") => createTAZActivitySimSkimmer(beamServices, tazClustering)
      case (h3Clustering: H3Clustering, "activitySim")   => createH3ActivitySimSkimmer(beamServices, h3Clustering)
      case (clustering, skimsKind) =>
        throw new IllegalArgumentException(
          s"Unexpected pair: skims kind ($skimsKind) with clustering ($clustering)"
        )
    }
  }

  private val additionalSkimFileNamePart = ".UrbanSim"

  private def createTAZActivitySimSkimmer(
    beamServices: BeamServices,
    tazClustering: TAZClustering
  ): ActivitySimSkimmer =
    new ActivitySimSkimmer(beamServices.matsimServices, beamServices.beamScenario, beamServices.beamConfig) {
      override def writeToDisk(event: IterationEndsEvent): Unit = {
        ProfilingUtils.timed(s"writeFullSkims on iteration ${event.getIteration}", v => logger.info(v)) {
          val filePath = event.getServices.getControlerIO.getIterationFilename(
            event.getServices.getIterationNumber,
            skimFileBaseName + additionalSkimFileNamePart + ".TAZ.Full.csv.gz"
          )
          val hour = beamServices.beamConfig.beam.urbansim.backgroundODSkimsCreator.peakHour.toInt
          val origins = tazClustering.tazTreeMap.getTAZs
            .map(taz => GeoUnit.TAZ(taz.tazId.toString, taz.coord, taz.areaInSquareMeters))
            .toSeq

          writeSkimsForTimePeriods(origins, origins, filePath)
          logger.info(s"Written UrbanSim peak skims for hour $hour to $filePath")
        }
      }
    }

  private def createH3ActivitySimSkimmer(beamServices: BeamServices, h3Clustering: H3Clustering): ActivitySimSkimmer =
    new ActivitySimSkimmer(beamServices.matsimServices, beamServices.beamScenario, beamServices.beamConfig) {
      override def writeToDisk(event: IterationEndsEvent): Unit = {
        ProfilingUtils.timed(s"writeFullSkims on iteration ${event.getIteration}", v => logger.info(v)) {
          val filePath = event.getServices.getControlerIO.getIterationFilename(
            event.getServices.getIterationNumber,
            skimFileBaseName + additionalSkimFileNamePart + ".H3.Full.csv.gz"
          )

          val hour = beamServices.beamConfig.beam.urbansim.backgroundODSkimsCreator.peakHour.toInt
          val origins: Seq[GeoUnit.H3] = h3Clustering.h3Indexes.map { h3Index =>
            val wgsCenter = H3Wrapper.wgsCoordinate(h3Index.index).coord
            val utmCenter = beamServices.geo.wgs2Utm(wgsCenter)
            val areaInSquareMeters = H3Wrapper.hexAreaM2(h3Index.index.resolution)
            GeoUnit.H3(h3Index.index.value, utmCenter, areaInSquareMeters)
          }

          writeSkimsForTimePeriods(origins, origins, filePath)
          logger.info(s"Written UrbanSim peak skims for hour $hour to $filePath")
        }
      }
    }

  private def createTAZOdSkimmer(beamServices: BeamServices, tazClustering: TAZClustering): ODSkimmer =
    new ODSkimmer(beamServices.matsimServices, beamServices.beamScenario, beamServices.beamConfig) {
      override def writeToDisk(event: IterationEndsEvent): Unit = {
        ProfilingUtils.timed(s"writeFullSkims on iteration ${event.getIteration}", v => logger.info(v)) {
          val filePath = event.getServices.getControlerIO.getIterationFilename(
            event.getServices.getIterationNumber,
            skimFileBaseName + additionalSkimFileNamePart + ".TAZ.Full.csv.gz"
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
            skimFileBaseName + additionalSkimFileNamePart + ".H3.Full.csv.gz"
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
