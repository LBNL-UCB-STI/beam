package beam.utils.analysis.r5vsgh

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse}
import beam.router.FreeFlowTravelTime
import beam.router.Modes.BeamMode
import beam.router.graphhopper.GraphHopperRouteResolver
import beam.router.model.BeamPath
import beam.router.r5.{R5Wrapper, WorkerParameters}
import beam.sim.BeamHelper
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import beam.utils.map.GpxWriter
import beam.utils.{FileUtils, ProfilingUtils}
import com.conveyal.osmlib.OSM
import com.graphhopper.GHResponse
import org.matsim.api.core.v01.population.{Person => MatsimPerson}
import org.matsim.api.core.v01.Id
import scopt.OptionParser

import scala.collection.mutable.ListBuffer

/**
 * See issue #2804 Get R5 vs. CCH Graphhopper performance for urbansim routes
 * https://github.com/LBNL-UCB-STI/beam/issues/2804
 *
 * See README.rst
 *
 * Run from gradle:
 * ./gradlew :execute -PmainClass=beam.utils.analysis.r5vsgh.R5vsGraphHopper -PmaxRAM=4 \
 *   -PappArgs="['--config', 'test/input/texas/austin-prod-200k.conf', '--population-sampling-factor', '0.1']"
 */
object R5vsGraphHopper extends BeamHelper {

  //noinspection DuplicatedCode
  def main(args: Array[String]): Unit = {
    val (_, cfg) = prepareConfig(args, isConfigArgRequired = true)
    val arguments = parseArguments(args)

    val workerParams: WorkerParameters = WorkerParameters.fromConfig(cfg)
    val r5Wrapper = new R5Wrapper(workerParams, new FreeFlowTravelTime, travelTimeNoiseFraction = 0)

    val utm2Wgs = workerParams.geo.utm2Wgs

    val outputDir = s"${workerParams.outputDirectory}/R5vsGraphHopper"
    FileUtils.createDirectoryIfNotExists(outputDir)
    val gpxOutputDir = s"$outputDir/gpx"
    FileUtils.createDirectoryIfNotExists(gpxOutputDir)

    // One may also use `readUtmPlanCsv("/path/to/plan.csv.gz")`
    val personPlanODs = takePlans(
      workerParams.scenario,
      arguments.populationSamplingFactor.getOrElse(1.0)
    )

    val odsCount = personPlanODs.values.map(_.size).sum
    logger.info(s"Origin-Destination pairs count: $odsCount")

    //
    // R5
    //

    val r5PersonResponses =
      collection.mutable.Map.empty[Id[MatsimPerson], ListBuffer[RoutingResponse]]
    val r5Results = ListBuffer.empty[R5vsGHResultRoute]
    var r5ErrorCount = 0

    ProfilingUtils.timed("R5 run", x => logger.info(x)) {
      var i: Int = 0
      var progressBar: Double = 0.0
      var progressBarStep: Double = 0.1
      personPlanODs.foreach { case (personId, planODs) =>
        planODs.foreach { case PlanOD(origin, destination) =>
          val (origWgs, destWgs) = (utm2Wgs.transform(origin), utm2Wgs.transform(destination))

          val carStreetVehicle = getStreetVehicle(
            s"dummy-car-for-r5-vs-gh-$personId-$i",
            BeamMode.CAV,
            origin
          )
          val req = baseRoutingRequest.copy(
            originUTM = origin,
            destinationUTM = destination,
            streetVehicles = Vector(carStreetVehicle),
            withTransit = false
          )
          val (r5Resp, executionTimeMs) = ProfilingUtils.timed { r5Wrapper.calcRoute(req) }

          val maybeTravelPath: Option[BeamPath] =
            r5Resp.itineraries.headOption.flatMap(_.legs.headOption).map(_.beamLeg.travelPath)

          val resultRoute: R5vsGHResultRoute = maybeTravelPath match {
            case None =>
              R5vsGHResultRoute(
                personId, origWgs.getX, origWgs.getY,
                destWgs.getX, destWgs.getY,
                0, 0.0, 0, executionTimeMs,
                isError = true,
                comment = "Beam Leg not found"
              )

            case Some(travelPath) =>
              val numberOfLinks = travelPath.linkIds.size
              val distanceInMeters = travelPath.distanceInM
              val travelTime = travelPath.linkTravelTime.sum.toLong  // time is in seconds
              val (isError, comment) =
                isErrorRoutingResponse(distanceInMeters, travelTime)

              R5vsGHResultRoute(
                personId, origWgs.getX, origWgs.getY,
                destWgs.getX, destWgs.getY,
                numberOfLinks, distanceInMeters, travelTime,
                executionTimeMs, isError, comment
              )
          }

          if (resultRoute.isError) {
            r5ErrorCount += 1
          } else {
            r5PersonResponses
              .getOrElseUpdate(personId, ListBuffer.empty)
              .append(r5Resp)
          }

          i += 1
        }

        val currentProgress = i.toDouble / odsCount
        if (progressBar == 0.0 || currentProgress >= progressBar) {
          logger.info(s"R5 progress: ${(progressBar * 100).intValue()}%")
          if (currentProgress >= progressBar) progressBar += progressBarStep
        }
      }
      logger.info("R5 completed. Errors count: {}", r5ErrorCount)
    }

    //
    // GraphHopper
    //

    val ghLocation = s"$outputDir/ghLocation"
    GraphHopperRouteResolver.createGHLocationFromR5(
      workerParams.transportNetwork,
      new OSM(workerParams.beamConfig.beam.routing.r5.osmMapdbFile),
      ghLocation
    )
    val gh = new GraphHopperRouteResolver(ghLocation)

    val ghPersonResponses =
      collection.mutable.Map.empty[Id[MatsimPerson], ListBuffer[GHResponse]]
    val ghResults = ListBuffer.empty[R5vsGHResultRoute]
    var ghErrorCount = 0

    ProfilingUtils.timed("GraphHopper run", x => logger.info(x)) {
      var i: Int = 0

      var progressBar: Double = 0.0
      var progressBarStep: Double = 0.1
      personPlanODs.foreach { case (personId, planODs) =>
        planODs.foreach { case PlanOD(origin, destination) =>
          val (origWgs, destWgs) = (utm2Wgs.transform(origin), utm2Wgs.transform(destination))

          val ((_, ghResp), executionTimeMs) = ProfilingUtils.timed { gh.route(origWgs, destWgs) }

          val resultRoute: R5vsGHResultRoute = if (ghResp.hasErrors) {
            R5vsGHResultRoute(
              personId, origWgs.getX, origWgs.getY,
              destWgs.getX, destWgs.getY,
              0, 0.0, 0, executionTimeMs,
              isError = true,
              comment = unwindErrorMessage(ghResp.getErrors)
            )

          } else {
            val path = ghResp.getBest
            val numberOfLinks = path.getPoints.size()
            val distanceInMeters = path.getDistance
            val travelTime = path.getTime / 1000  // time is in millis
            val (isError, comment) =
              isErrorRoutingResponse(distanceInMeters, travelTime)

            R5vsGHResultRoute(
              personId, origWgs.getX, origWgs.getY,
              destWgs.getX, destWgs.getY,
              numberOfLinks, distanceInMeters, travelTime,
              executionTimeMs, isError, comment
            )
          }

          if (resultRoute.isError) {
            ghErrorCount += 1
          } else {
            ghPersonResponses
              .getOrElseUpdate(personId, ListBuffer.empty)
              .append(ghResp)
          }

          i += 1
        }

        val currentProgress = i.toDouble / odsCount
        if (progressBar == 0.0 || currentProgress >= progressBar) {
          logger.info(s"GH progress: ${(progressBar * 100).intValue()}%")
          if (currentProgress >= progressBar) progressBar += progressBarStep
        }
      }
      logger.info("GH completed. Errors count: {}", ghErrorCount)
    }

    //
    // Results
    //

    val r5ResultsOutput = s"$outputDir/r5_routes.csv.gz"
    writeResultRoutesCsv(r5ResultsOutput, r5Results)
    logger.info(s"R5 results written to: $r5ResultsOutput")

    val ghResultsOutput = s"$outputDir/gh_routes.csv.gz"
    writeResultRoutesCsv(ghResultsOutput, ghResults)
    logger.info(s"GH results written to: $ghResultsOutput")

    //
    // GPX
    //

    r5PersonResponses.foreach { case (personId, r5Responses) =>
      val gpxPoints = r5ResponsesToGpxPoints(
        r5Responses, workerParams.networkHelper, utm2Wgs
      )
      val personR5RoutesGpxFile = gpxOutputDir + s"/r5_$personId.gpx"
      GpxWriter.write(personR5RoutesGpxFile, gpxPoints)
    }

    ghPersonResponses.foreach { case (personId, ghResponses) =>
      val gpxPoints = ghResponsesToGpxPoints(personId, ghResponses)
      val personGHRoutesGpxFile = gpxOutputDir + s"/gh_$personId.gpx"
      GpxWriter.write(personGHRoutesGpxFile, gpxPoints)
    }
  }

  /** RoutingRequest being copied for each R5 routing request */
  private val baseRoutingRequest: RoutingRequest = {
    val personAttribs = AttributesOfIndividual(
      householdAttributes = HouseholdAttributes("48-453-001845-2:117138", 70000.0, 1, 1, 1),
      modalityStyle = None,
      isMale = true,
      availableModes = Seq(BeamMode.CAR),
      valueOfTime = 17.15686274509804,
      age = None,
      income = Some(70000.0)
    )
    RoutingRequest(
      originUTM = new Location(2961475.272057291, 3623253.4635826824),
      destinationUTM = new Location(2967932.9521744307, 3635449.522501624),
      departureTime = 30600,
      withTransit = true,
      streetVehicles = Vector.empty,
      attributesOfIndividual = Some(personAttribs)
    )
  }

  //noinspection DuplicatedCode,SameParameterValue
  private def getStreetVehicle(
    id: String,
    beamMode: BeamMode,
    location: Location
  ): StreetVehicle = {
    val vehicleTypeId = beamMode match {
      case BeamMode.CAR | BeamMode.CAV =>
        "CAV"
      case BeamMode.BIKE =>
        "FAST-BIKE"
      case BeamMode.WALK =>
        "BODY-TYPE-DEFAULT"
      case _ =>
        throw new IllegalStateException(s"Don't know what to do with BeamMode $beamMode")
    }

    StreetVehicle(
      id = Id.createVehicleId(id),
      vehicleTypeId = Id.create(vehicleTypeId, classOf[BeamVehicleType]),
      locationUTM = SpaceTime(loc = location, time = 30600),
      mode = beamMode,
      asDriver = true
    )
  }

  private def isErrorRoutingResponse(
    distanceInMeters: Double,
    travelTime: Long
  ): (Boolean, String) = {
    val comment = new StringBuilder

    val isDistanceCorrect =
          distanceInMeters > 0.0 &&
          !distanceInMeters.isNaN &&
          !distanceInMeters.isInfinity
    if (!isDistanceCorrect) {
      comment.append(s"distanceInMeters is $distanceInMeters; ")
    }

    val isTravelTimePositive = travelTime > 0
    if (!isTravelTimePositive) {
      comment.append(s"travelTime is $travelTime (negative); ")
    }

    (
      !isDistanceCorrect, // || !isXXX
      comment.toString()
    )
  }

  //
  // R5vsGraphHopper-specific CLI
  //

  case class Arguments(
    populationSamplingFactor: Option[Double] = None,
    plan: Option[String] = None
  )

  private def parseArguments(parser: OptionParser[Arguments], args: Array[String]): Option[Arguments] = {
    parser.parse(args, init = Arguments())
  }

  def parseArguments(args: Array[String]): Arguments =
    parseArguments(buildParser, args) match {
      case Some(pArgs) => pArgs
      case None =>
        throw new IllegalArgumentException(
          "Arguments provided were unable to be parsed. See above for reasoning."
        )
    }

  private def buildParser: OptionParser[Arguments] = {
    new scopt.OptionParser[Arguments]("R5vsGraphHopper") {
      opt[Double]("population-sampling-factor")
        .action { (value, args) => args.copy(populationSamplingFactor = Option(value)) }
        .validate { value =>
          if (value <= 0.0 || value > 1.0) {
            failure("Population sampling factor should be within (0.0 < x < 1.0]")
          } else success
        }
        .text("Population sampling factor: (0.0 < x < 1.0]. Default: 1.0")
      opt[String]("plan")
        .action { (value, args) => args.copy(plan = Option(value)) }
        .validate { value =>
          if (value.trim.isEmpty) {
            failure("Plan file location cannot be empty")
          } else success
        }
        .text("""Plan (".csv" or ".csv.gz") file location""")
    }
  }
}
