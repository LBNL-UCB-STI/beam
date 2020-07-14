package beam.utils.analysis

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse}
import beam.router.FreeFlowTravelTime
import beam.router.Modes.BeamMode
import beam.router.r5.{R5Wrapper, WorkerParameters}
import beam.sim.BeamHelper
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import beam.utils.ProfilingUtils
import com.typesafe.config.Config
import org.matsim.api.core.v01.Id

import scala.collection.mutable.ListBuffer

object R5vsCCHPerformance extends BeamHelper {

  private val baseRoutingRequest: RoutingRequest = {
    val originUTM = new Location(2961475.272057291, 3623253.4635826824)
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
      originUTM = originUTM,
      destinationUTM = new Location(2967932.9521744307, 3635449.522501624),
      departureTime = 30600,
      withTransit = true,
      streetVehicles = Vector.empty,
      attributesOfIndividual = Some(personAttribs)
    )
  }

  def main(args: Array[String]): Unit = {
    val (arguments, cfg) = prepareConfig(args, isConfigArgRequired = true)
    val r5Wrapper = createR5Wrapper(cfg)
    val ods = readPlan(arguments.planLocation.get)
    val responses = ListBuffer.empty[RoutingResponse]

    ProfilingUtils.timed("R5 performance check", x => logger.info(x)) {
      var i: Int = 0
      ods.foreach { p =>
        val (origin, dest) = p
        i += 1

        val carStreetVehicle = getStreetVehicle(
          s"dummy-car-for-r5-vs-cch $i",
          BeamMode.CAV,
          baseRoutingRequest.originUTM
        )

        val req = baseRoutingRequest.copy(
          originUTM = origin,
          destinationUTM = dest,
          streetVehicles = Vector(carStreetVehicle),
          withTransit = false )
          responses += r5Wrapper.calcRoute(req)
      }
    }
    logger.info("R5 performance check completed. Routes count: {}", responses.size)

    println
  }

  private def createR5Wrapper(cfg: Config): R5Wrapper = {
    val workerParams: WorkerParameters = WorkerParameters.fromConfig(cfg)
    new R5Wrapper(workerParams, new FreeFlowTravelTime, travelTimeNoiseFraction = 0)
  }

  private def getStreetVehicle(id: String, beamMode: BeamMode, location: Location): StreetVehicle = {
    val vehicleTypeId = beamMode match {
      case BeamMode.CAR | BeamMode.CAV =>
        "CAV"
      case BeamMode.BIKE =>
        "FAST-BIKE"
      case BeamMode.WALK =>
        "BODY-TYPE-DEFAULT"
      case x =>
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

  private def readPlan(path: String): Seq[(Location, Location)] = {
    import beam.utils.csv.GenericCsvReader

    val (iterator, closeable) = GenericCsvReader.readAs[(String, String, String)](
      path,
      { entry =>
        (
          entry.get("personId"),
          entry.get("activityLocationX"),
          entry.get("activityLocationY")
        )
      },
      { t =>
        val (personId, x, y) = t
        personId != null && x != null && x.nonEmpty && y != null && y.nonEmpty
      }
    )

    val buffer = ListBuffer.empty[(Location, Location)]

    var currentPerson: String = null
    var prevLocation: Location = null

    try {
      iterator.foreach { t =>
        val (personId, x, y) = t

        if (personId != currentPerson) {
          currentPerson = personId
          prevLocation = null
        }

        if (x.nonEmpty && y.nonEmpty) {
          val location = new Location(x.toDouble, y.toDouble)

          if (prevLocation != null) {
            if (prevLocation != null) {
              buffer += ((prevLocation, location))
            }
          }

          prevLocation = location
        }
      }
    } finally {
      closeable.close()
    }

    buffer
  }
}
