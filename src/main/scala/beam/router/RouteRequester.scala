package beam.router

import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse}
import beam.router.cch.CchWrapper
import beam.router.graphhopper.{CarGraphHopperWrapper, GraphHopperWrapper}
import beam.router.r5.{CarWeightCalculator, R5Parameters, R5Wrapper}
import com.conveyal.osmlib.OSM
import com.google.common.util.concurrent.ThreadFactoryBuilder
import org.matsim.core.router.util.TravelTime
import org.matsim.core.utils.misc.Time

import java.io.File
import java.nio.file.Paths
import java.util.concurrent.{ExecutorService, Executors}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.reflect.io.Directory

trait RouteRequester {

  def route(request: RoutingRequest): RoutingResponse = {
    val carMode = Modes.BeamMode.CAR
    if (!request.withTransit && request.streetVehicles.exists(_.mode == carMode)) {
      routeInner(request.copy(streetVehicles = request.streetVehicles.filter(_.mode == carMode)))
    } else {
      RoutingResponse(
        Seq(),
        request.requestId,
        Some(request),
        isEmbodyWithCurrentTravelTime = false,
        triggerId = request.triggerId
      )
    }
  }

  protected def routeInner(request: RoutingRequest): RoutingResponse
}

class CchRouteRequester(workerParams: R5Parameters, travelTime: TravelTime) extends RouteRequester {
  private val nativeCCH = CchWrapper(workerParams)
  nativeCCH.rebuildNativeCCHWeights(travelTime)

  override def routeInner(request: RoutingRequest): RoutingResponse = {
    nativeCCH.calcRoute(request)
  }
}

class R5RouteRequester(workerParams: R5Parameters, travelTime: TravelTime) extends RouteRequester {

  private val r5: R5Wrapper = new R5Wrapper(
    workerParams,
    travelTime,
    workerParams.beamConfig.beam.routing.r5.travelTimeNoiseFraction
  )

  override protected def routeInner(request: RoutingRequest): RoutingResponse = {
    r5.calcRoute(request)
  }
}

class GHRouteRequester(workerParams: R5Parameters, travelTime: TravelTime) extends RouteRequester {
  private val binToCarGraphHopper: Map[Int, GraphHopperWrapper] = createCarGraphHoppers(workerParams, travelTime)

  override protected def routeInner(request: RoutingRequest): RoutingResponse = {
    binToCarGraphHopper(Math.floor(request.departureTime / workerParams.beamConfig.beam.agentsim.timeBinSize).toInt)
      .calcRoute(request)
  }

  private def createCarGraphHoppers(
    workerParams: R5Parameters,
    travelTime: TravelTime
  ): Map[Int, GraphHopperWrapper] = {
    val numOfThreads: Int =
      if (Runtime.getRuntime.availableProcessors() <= 2) 1
      else Runtime.getRuntime.availableProcessors() - 2

    val execSvc: ExecutorService = Executors.newFixedThreadPool(
      numOfThreads,
      new ThreadFactoryBuilder().setDaemon(true).setNameFormat("init-gh-%d").build()
    )
    val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(execSvc)

    val graphHopperDir: String = Paths.get(workerParams.beamConfig.beam.inputDirectory, "graphhopper").toString
    val carGraphHopperDir: String = Paths.get(graphHopperDir, "car").toString
    val carRouter = workerParams.beamConfig.beam.routing.carRouter
    val noOfTimeBins = Math
      .floor(
        Time.parseTime(workerParams.beamConfig.beam.agentsim.endTime) /
        workerParams.beamConfig.beam.agentsim.timeBinSize
      )
      .toInt

    val id2Link: Map[Int, (Location, Location)] = workerParams.networkHelper.allLinks
      .map(x => x.getId.toString.toInt -> (x.getFromNode.getCoord -> x.getToNode.getCoord))
      .toMap

    new Directory(new File(carGraphHopperDir)).deleteRecursively()

    val carWeightCalculator = new CarWeightCalculator(workerParams)
    val graphHopperInstances = noOfTimeBins

    val futures = (0 until graphHopperInstances).map { i =>
      Future {
        val ghDir = Paths.get(carGraphHopperDir, i.toString).toString

        val wayId2TravelTime = workerParams.networkHelper.allLinks.toSeq
          .map(link =>
            link.getId.toString.toLong ->
            carWeightCalculator.calcTravelTime(
              link.getId.toString.toInt,
              travelTime,
              i * workerParams.beamConfig.beam.agentsim.timeBinSize
            )
          )
          .toMap

        GraphHopperWrapper.createCarGraphDirectoryFromR5(
          carRouter,
          workerParams.transportNetwork,
          new OSM(workerParams.beamConfig.beam.routing.r5.osmMapdbFile),
          ghDir,
          wayId2TravelTime
        )

        i -> new CarGraphHopperWrapper(
          carRouter,
          ghDir,
          workerParams.geo,
          workerParams.vehicleTypes,
          workerParams.fuelTypePrices,
          wayId2TravelTime,
          id2Link,
          workerParams.beamConfig.beam.routing.gh.useAlternativeRoutes
        )
      }(executionContext)
    }

    Await.result(Future.sequence(futures)(implicitly, executionContext), 20.minutes).toMap
  }
}
