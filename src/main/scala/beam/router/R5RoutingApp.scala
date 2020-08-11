package beam.router

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Identify, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.pattern._
import akka.util.Timeout
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse, UpdateTravelTimeLocal}
import beam.router.Modes.BeamMode.CAR
import beam.sim.config.BeamConfig
import beam.sim.{BeamHelper, BeamWarmStart}
import beam.utils.{FileUtils, LoggingUtil}
import com.typesafe.scalalogging.LazyLogging
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.syntax._
import org.matsim.api.core.v01.Id

import scala.concurrent.Await
import scala.concurrent.duration._

class RoutingHandler(val workerRouter: ActorRef) extends FailFastCirceSupport {
  implicit val timeout: Timeout = new Timeout(10, TimeUnit.SECONDS)
  import beam.utils.json.AllNeededFormats._

  val route: Route = {
    path("find-route") {
      post {
        entity(as[RoutingRequest]) { request =>
          complete(workerRouter.ask(request).mapTo[RoutingResponse])
        }
      }
    }
  }
}

object CustomExceptionHandling extends LazyLogging {

  def handler: ExceptionHandler = ExceptionHandler {
    case t: Throwable =>
      extractClientIP { remoteAddress =>
        extractRequest { request =>
          val msg = s"Exception during processing $request from $remoteAddress: ${t.getMessage}"
          logger.error(msg, t)
          complete(HttpResponse(StatusCodes.InternalServerError, entity = msg))
        }
      }
  }
}

object R5RoutingApp extends BeamHelper {
  import beam.utils.json.AllNeededFormats._
  implicit val timeout: Timeout = new Timeout(600, TimeUnit.SECONDS)

  def main(args: Array[String]): Unit = {
    val (arg, cfg) = prepareConfig(args, isConfigArgRequired = true)
    val beamCfg = BeamConfig(cfg)
    val outputDirectory = FileUtils.getConfigOutputFile(
      beamCfg.beam.outputs.baseOutputDirectory,
      beamCfg.beam.agentsim.simulationName + "_R5RoutingApp",
      beamCfg.beam.outputs.addTimestampToOutputDirectory
    )
    LoggingUtil.initLogger(outputDirectory, true)

    implicit val actorSystem: ActorSystem = ActorSystem("R5RoutingApp", cfg)

    val workerRouter: ActorRef = actorSystem.actorOf(Props(classOf[RoutingWorker], cfg), name = "workerRouter")
    val f = Await.result(workerRouter ? Identify(0), Duration.Inf)
    logger.info("R5RoutingWorker is initialized!")

    val isWarmMode = beamCfg.beam.warmStart.enabled
    logger.info(s"warmStart isEnabled?: $isWarmMode")
    if (isWarmMode) {
      val warmStart = BeamWarmStart(beamCfg)
      warmStart.readTravelTime.foreach { travelTime =>
        workerRouter ! UpdateTravelTimeLocal(travelTime)
        logger.info("Send `UpdateTravelTimeLocal`")
      }
    }
    val interface = "0.0.0.0"
    val port = 9000
    val routingHandler = new RoutingHandler(workerRouter)
    val boostedRoute = handleExceptions(CustomExceptionHandling.handler)(routingHandler.route)
    Http().bindAndHandle(boostedRoute, interface, port)
    logger.info(s"Http server is ready and bound to $interface:$port")
  }

  def printRoutingRequestJsonExample(): Unit = {
    val geoUtils = new beam.sim.common.GeoUtils {
      override def localCRS: String = "epsg:26910"
    }
    val startUTM = geoUtils.wgs2Utm(new Location(-122.4750527, 38.504534))
    val endUTM = geoUtils.wgs2Utm(new Location(-122.0371486, 37.37157))
    val departureTime = 20131
    val bodyStreetVehicle = StreetVehicle(
      Id.createVehicleId("1"),
      Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
      new SpaceTime(startUTM, time = departureTime),
      CAR,
      asDriver = true
    )
    val routingRequest = RoutingRequest(
      originUTM = startUTM,
      destinationUTM = endUTM,
      departureTime = departureTime,
      withTransit = false,
      streetVehicles = Vector(bodyStreetVehicle)
    )

    println(routingRequest.asJson.toString())
  }
}
