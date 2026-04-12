package beam.agentsim.agents.vehicles

import beam.agentsim.agents.freight.FreightActivityType
import beam.agentsim.agents.vehicles.VehicleCategory.{
  Class456Vocational,
  Class78Tractor,
  Class78Vocational,
  MediumDutyPassenger
}
import beam.agentsim.agents.vehicles.VehicleEmissions.Emissions.{formatName, EmissionType}
import beam.agentsim.agents.vehicles.VehicleEmissions.EmissionsProfile.EmissionsProcess
import beam.agentsim.agents.vehicles.VehicleEmissions.EmissionsRateFilterStore.EmissionsRateFilter
import beam.agentsim.events.{LeavingParkingEvent, PathTraversalEvent}
import beam.agentsim.infrastructure.ParkingInquiry.ParkingActivityType._
import beam.router.skim.event.EmissionsSkimmerEvent
import beam.sim.BeamServices
import beam.sim.common.DoubleTypedRange
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions.RatesFilter
import beam.utils.BeamVehicleUtils.convertRecordStringToDoubleTypedRange
import beam.utils.{BeamVehicleUtils, NetworkHelper, ParquetReader}
import com.typesafe.scalalogging.LazyLogging
import com.univocity.parsers.common.record.Record
import com.univocity.parsers.csv.{CsvParser, CsvParserSettings}
import org.apache.avro.AvroRuntimeException
import org.apache.avro.generic.GenericRecord
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.io.IOUtils
import org.slf4j.LoggerFactory

import java.nio.file.Paths
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class VehicleEmissions(
  vehicleTypesBasePaths: IndexedSeq[String],
  vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType],
  pollutantsFilter: String,
  ratesFilter: RatesFilter
) {
  import VehicleEmissions._
  import EmissionsProfile._
  private val settings = new CsvParserSettings()
  settings.setHeaderExtractionEnabled(true)
  settings.detectFormatAutomatically()
  private val csvParser = new CsvParser(settings)

  private lazy val emissionsRatesFilterStore = new EmissionsRateFilterStore(
    vehicleTypesBasePaths,
    emissionsRateFilePathsByVehicleType = vehicleTypes.values.map(x => (x, x.emissionsRatesFile)).toIndexedSeq
  )

  private lazy val vehicleOperationTimeTrieMap: TrieMap[Id[BeamVehicle], Double] =
    TrieMap.empty[Id[BeamVehicle], Double]

  Emissions.setFilter(pollutantsFilter.split(","))

  def getEmissionsProfileInGram(
    vehicleActivityData: IndexedSeq[BeamVehicle.VehicleActivityData],
    vehicleActivity: Class[_ <: org.matsim.api.core.v01.events.Event],
    beamServices: BeamServices
  ): Option[EmissionsProfile] = {
    val emissionsProfiles = for {
      data <- vehicleActivityData
      EmissionsProcessAndRatesStore(process, ratesStore) <- identifyProcesses(
        data,
        vehicleActivity,
        emissionsRatesFilterStore
      )
      rates <- getRatesUsing(data, process, ratesStore, beamServices.networkHelper)

    } yield {
      val emissions = calculationMap(process)(
        rates,
        data,
        vehicleOperationTimeTrieMap,
        beamServices.beamConfig.beam.agentsim.agents.vehicles.emissions
      )
      if (!emissions.notValid && beamServices.beamConfig.beam.agentsim.agents.vehicles.emissions.skims) {
        // Create and process EmissionsSkimmerEvent
        beamServices.matsimServices.getEvents.processEvent(
          EmissionsSkimmerEvent(
            time = data.linkStartTime,
            linkId = data.linkId,
            vehicleType = data.vehicleType.id.toString,
            emissions = emissions,
            emissionsProcess = process,
            travelTime = data.linkTravelTime.getOrElse(0.0),
            parkingDuration = data.parkingDuration.getOrElse(0.0),
            beamServices = beamServices
          )
        )
      }
      process -> emissions
    }

    if (emissionsProfiles.isEmpty) None else Some(EmissionsProfile(emissionsProfiles.toMap))
  }

  private def findString[T](
    map: Map[String, T],
    value: String,
    preferEmptyKey: Boolean = true
  ): Option[(String, T)] = {
    if (preferEmptyKey) {
      map.find(_._1 == "").orElse(map.find(_._1 == value))
    } else {
      map.find(_._1 == value).orElse(map.find(_._1 == ""))
    }
  }

  private def findInterval[T](
    map: Map[DoubleTypedRange, T],
    value: Double,
    preferWiderRanges: Boolean = true
  ): Option[(DoubleTypedRange, T)] = {
    val filteredMap = map.filter(_._1.has(value))
    if (filteredMap.isEmpty) {
      map.headOption.map { _ =>
        map.minBy { case (range, _) =>
          val center = (range.lowerBound + range.upperBound) / 2.0
          math.abs(center - value)
        }
      }
    } else {
      Some(filteredMap.maxBy { case (range, _) =>
        (range.upperBound - range.lowerBound) * (if (preferWiderRanges) 1 else -1)
      })
    }
  }

  private def getRatesUsing(
    data: BeamVehicle.VehicleActivityData,
    process: EmissionsProcess,
    ratesStore: EmissionsRateFilter,
    networkHelper: NetworkHelper
  ): Option[Emissions] = {
    val speedMph =
      data.averageSpeed.map(BeamVehicleUtils.convertFromMetersPerSecondToMilesPerHour).getOrElse(0.0)
    val soakTimeMin = data.parkingDuration.map(_ / 60.0).getOrElse(0.0)
    val county = data.taz.flatMap(_.county).getOrElse("").trim.toLowerCase
    val roadCategory =
      networkHelper
        .getLink(data.linkId)
        .flatMap(link => Option(link.getAttributes.getAttribute("type")).map(_.toString.toLowerCase))
        .getOrElse("unclassified")
    val processStr = process.toString
    val activityValue = if (usesTimeLikeActivityBin(process)) soakTimeMin else speedMph
    val preferWiderActivityRanges =
      if (usesTimeLikeActivityBin(process)) !ratesFilter.soakTime.contains(processStr)
      else !ratesFilter.speed.contains(processStr)

    val ratesMaybe = for {
      (_, countyFilter)   <- findString(ratesStore, county, !ratesFilter.county.contains(processStr))
      (_, processFilter)  <- findString(countyFilter, process.toString, false)
      (_, roadFilter)     <- findString(processFilter, roadCategory, !ratesFilter.roadCategory.contains(processStr))
      (_, activityFilter) <- findInterval(roadFilter, activityValue, preferWiderActivityRanges)
      rates               <- Some(activityFilter)
    } yield rates

    ratesMaybe
  }
}

object VehicleEmissions extends LazyLogging {

  case class EmissionsProcessAndRatesStore(process: EmissionsProcess, ratesStore: EmissionsRateFilter)

  private def usesTimeLikeActivityBin(process: EmissionsProcess): Boolean =
    Set(EmissionsProfile.STREX, EmissionsProfile.DIURN, EmissionsProfile.HOTSOAK, EmissionsProfile.RUNLOSS).contains(
      process
    )

  object EmissionsRateFilterStore {

    // county -> (emissionProcess -> (roadCategory -> (activityBin -> rate)))
    type EmissionsRateFilter = Map[
      String, // county
      Map[
        String, // emissionProcess
        Map[
          String, // road category
          Map[
            DoubleTypedRange, // activity bin (speed or soak time, depending on process)
            Emissions // rate
          ]
        ]
      ]
    ]
  }

  case class Emissions(values: Map[EmissionType, Double] = Map.empty) {
    def notValid: Boolean = values.values.sum <= 0

    def *(factor: Double): Emissions =
      Emissions(values.map { case (k, v) => k -> (v * factor) })

    def /(factor: Double): Emissions = {
      if (factor == 0) {
        logger.error("Dividing Emissions rates by zero!!!")
        this
      } else this * (1 / factor)
    }

    def +(other: Emissions): Emissions =
      Emissions((values.keySet ++ other.values.keySet).map { key =>
        key -> (values.getOrElse(key, 0.0) + other.values.getOrElse(key, 0.0))
      }.toMap)

    def +=(other: Emissions): Emissions = {
      Emissions(
        (values.keySet ++ other.values.keySet).map { key =>
          key -> (values.getOrElse(key, 0.0) + other.values.getOrElse(key, 0.0))
        }.toMap
      )
    }

    def get(emissionType: EmissionType): Option[Double] = values.get(emissionType)

    override def toString: String =
      values.map { case (key, value) => s"${formatName(key)}=$value" }.mkString("Emissions(", ", ", ")")
  }

  object Emissions extends Enumeration {
    type EmissionType = Value
    val CH4, CO, CO2, HC, NH3, N2O, NOx, PM, PM10, PM25, ROG, SOx, TOG, BC = Value

    var filter: Option[List[EmissionType]] = None

    def setFilter(emissionsStr: Array[String]): Unit = {
      filter match {
        case None =>
          val toKeep = emissionsStr.flatMap(fromString)
          val allEmissions = values.toList
          filter = Some(allEmissions.filterNot(toKeep.contains))

          if (toKeep.nonEmpty) {
            val keeping = toKeep.map(_.toString).mkString(", ")
            val filtering = filter.map(_.map(_.toString).mkString(", ")).getOrElse("")
            logger.info(s"Keeping only the following pollutants: $keeping")
            logger.info(s"Filtering out: $filtering")
          }
        case _ =>
      }
    }

    def formatName(emissionType: EmissionType): String = emissionType match {
      case PM25 => "PM25"
      case _    => emissionType.toString
    }

    def fromString(s: String): Option[EmissionType] = {
      values.find(v => formatName(v).equalsIgnoreCase(s.trim))
    }

    def init(): Emissions = Emissions()

    def apply(values: (EmissionType, Double)*): Emissions = {
      new Emissions(values.filter(v => !this.filter.contains(v._1)).toMap)
    }

    def formatEmissions(emissions: Emissions): String =
      emissions.values.map { case (key, value) => formatEmission(formatName(key), value) }.mkString(", ")

    private def formatEmission(name: String, value: Double): String = f"$name: $value%.2f"
  }

  case class EmissionsProfile(values: Map[EmissionsProcess, Emissions] = Map.empty) {}

  object EmissionsProfile extends Enumeration {
    type EmissionsProcess = Value
    val RUNEX, IDLEX, STREX, HOTSOAK, DIURN, RUNLOSS, PMTW, PMBW, PRDUST, PTOEX = Value

    def init(): EmissionsProfile = EmissionsProfile()

    def apply(values: (EmissionsProcess, Emissions)*): EmissionsProfile = new EmissionsProfile(values.toMap)

    def join(
      emissionsProfile1: Option[EmissionsProfile],
      emissionsProfile2: Option[EmissionsProfile]
    ): Option[EmissionsProfile] = {
      (emissionsProfile1, emissionsProfile2) match {
        case (Some(ep1), Some(ep2)) => Some(EmissionsProfile(ep1.values ++ ep2.values))
        case (Some(ep1), _)         => Some(ep1)
        case (_, Some(ep2))         => Some(ep2)
        case _                      => None
      }
    }

    private def isIdlingDriving(
      data: BeamVehicle.VehicleActivityData,
      vehicleActivity: Class[_ <: org.matsim.api.core.v01.events.Event]
    ): Boolean = {
      val averageSpeed: Double = data.averageSpeed.getOrElse(0.0)
      val travelTime: Double = data.linkTravelTime.getOrElse(0.0)
      vehicleActivity == classOf[PathTraversalEvent] && averageSpeed < 2.24 && travelTime > 300
    }

    private def isIdlingParking(
      data: BeamVehicle.VehicleActivityData,
      vehicleActivity: Class[_ <: org.matsim.api.core.v01.events.Event]
    ): Boolean = {
      vehicleActivity == classOf[LeavingParkingEvent] && data.vehicleType.vehicleUse == VehicleUse.Freight
    }

    def identifyProcesses(
      data: BeamVehicle.VehicleActivityData,
      event: Class[_ <: org.matsim.api.core.v01.events.Event],
      emissionsRatesFilterStore: EmissionsRateFilterStore
    ): IndexedSeq[EmissionsProcessAndRatesStore] = {
      emissionsRatesFilterStore
        .getEmissionsRateFilterFor(data.vehicleType)
        .map(future => Await.result(future, 1.minute)) match {
        case Some(rateFilter) =>
          EmissionsProfile.values.flatMap {
            /**
              * IDLE activity should be the first element of VehicleActivity data sequence
              * the type is PathTraversalEvent because there is no difference, IDLE activity happens between other events
              *
              * Idle Exhaust (IDLEX) emissions refer to the emissions during extended idling events (i.e., a continuous
              * segment of vehicle activity that meets three criteria: all instantaneous vehicle speeds being lower
              * than 5 mph, the total distance of less than 1 mile, and the total duration of more than 5 minutes)
              * by heavy duty trucks. Extended idle may occur during loading or unloading goods, or to power accessories.
              * Idle exhaust is calculated only for heavy-duty trucks. For light duty vehicles, the idle events during
              * normal vehicle operation are already accounted for, i.e. RUNEX emission rates are based on driving
              * cycles that include normal idling events. IDLEX emission rates do not vary by temperature and humidity
              * and are not related to speed bins.
              * https://ww2.arb.ca.gov/sites/default/files/2021-03/emfac2021_volume_2_pl_handbook.pdf
              */
            case process @ IDLEX if isIdlingDriving(data, event) || isIdlingParking(data, event) =>
              Some(EmissionsProcessAndRatesStore(process, rateFilter))

            case process @ (RUNEX | PMBW | PMTW | RUNLOSS | PRDUST) if event == classOf[PathTraversalEvent] =>
              Some(EmissionsProcessAndRatesStore(process, rateFilter))

            case process @ PTOEX
                if event == classOf[PathTraversalEvent] && data.vehicleType.vehicleUse == VehicleUse.Freight =>
              Some(EmissionsProcessAndRatesStore(process, rateFilter))

            case process @ (STREX | DIURN | HOTSOAK | RUNLOSS) if event == classOf[LeavingParkingEvent] =>
              Some(EmissionsProcessAndRatesStore(process, rateFilter))

            case _ => None
          }.toIndexedSeq
        case _ => IndexedSeq.empty
      }
    }

    def fromString(process: String): Option[EmissionsProcess] = {
      val depot = FreightActivityType.Depot.toString
      val loading = FreightActivityType.Loading.toString
      val unloading = FreightActivityType.Unloading.toString
      process.toLowerCase match {
        // Running Exhaust Emissions (RUNEX) that come out of the vehicle tailpipe while traveling on the road.
        // TODO Embed it in PathTraversalEvent
        // xVMT by speed bin => gram/veh-mile
        case "running" | "runex" => Some(RUNEX)

        // Idle Exhaust Emissions (IDLEX) that come out of the vehicle tailpipe while it is operating but not traveling
        // any significant distance. This process captures emissions from heavy-duty vehicles that idle for
        // extended periods of time while loading or unloading goods. Idle exhaust is calculated only
        // for heavy-duty trucks.
        // TODO Embed it in LeavingParkingEvent when 1) it is freight Load/Unload 2) overnight parking
        // xNumber of Idle Hours (xParking Hour) => gram/veh-idle hour
        case "idling" | "idlex" | "extidlex" | "hotelling" | `depot` | `loading` | `unloading` => Some(IDLEX)

        // Start Exhaust Tailpipe Emissions (STREX) that occur when starting a vehicle. These emissions are independent
        // of running exhaust emissions and represent the emissions occurring during the initial time period when
        // a vehicle’s emissions after treatment system is warming up. The magnitude of these emissions is dependent
        // on how long the vehicle has been sitting prior to starting. Please note that STREX is defined differently
        // for heavy-duty diesel trucks than for other vehicles.
        // More details can be found in the EMFAC2014 Technical Support Document.
        // TODO Embed it in LeavingParkingEvent
        // xNumber of starts per Soak time => gram/veh-start
        case "start" | "strex" => Some(STREX)

        // Diurnal Evaporative HC Emissions (DIURN) that occur when rising ambient temperatures cause fuel evaporation
        // from vehicles sitting throughout the day. These losses are from leaks in the fuel system, fuel hoses,
        // connectors, as a result of the breakthrough of vapors from the carbon canister.
        // TODO Embed it in LeavingParkingEvent
        // xCold soak hours (xParking Hour) => gram/veh-hour
        case "diurnal" | "diurn" => Some(DIURN)

        // Hot Soak Evaporative HC Emissions (HOTSOAK) that begin immediately from heated fuels after a car stops its
        // engine operation and continue until the fuel tank reaches ambient temperature.
        // TODO Embed it in LeavingParkingEvent
        // xNumber of starts => gram/veh-start
        case "hotsoak" => Some(HOTSOAK)

        // Running Loss Evaporative HC Emissions (RUNLOSS) that occur as a result of hot fuel vapors escaping
        // from the fuel system or overwhelming the carbon canister while the vehicle is operating.
        // TODO Embed it in PathTraversalEvent and LeavingParkingEvent (loading/unloading/hotelling)
        // xRunning hours (xVHT) => gram/veh-hour
        case "runloss" => Some(RUNLOSS)

        // Tire Wear Particulate Matter Emissions (PMTW) that originate from tires as a result of wear.
        // TODO Embed it in PathTraversalEvent
        // xVMT => gram/veh-mile
        case "tirewear" | "pmtw" => Some(PMTW) // Embedded in PathTraversalEvent

        // Brake Wear Particulate Matter Emissions (PMBW) that originate from brake usage.
        // TODO Embed it in PathTraversalEvent
        // xVMT by speed bin => gram/veh-mile
        case "brakewear" | "pmbw" => Some(PMBW)

        // Paved Road Dust Particulate Matter Emissions (PRDUST) calculated using EPA AP-42 methodology.
        // Based on silt loading, vehicle weight, precipitation, and road type.
        // E = k * (SL^0.91) * (W^1.02) * (1 - P/N/4) with PM2.5/PM10 fractions applied.
        // xVMT => gram/veh-mile
        case "dust" | "road_dust" | "paved_road_dust" | "prdust" => Some(PRDUST)

        // Power take-off exhaust emissions during operation.
        // PTOEX is treated as a traversal exhaust process with speed-bin lookup, not as a parking/start process.
        case "ptoex" | "pto" | "power_take_off" => Some(PTOEX)

        // if process is not recognized then RUNEX emission will be used
        case _ =>
          logger.warn(s"Unrecognized emission process: $process")
          None
      }
    }

    private val workdayIdleFactor: Map[
      VehicleCategory.VehicleCategory,
      (BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions) => Double
    ] = Map(
      MediumDutyPassenger -> { (emissionsConfig: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions) =>
        emissionsConfig.workdayIdleTimeFraction.bus
      },
      Class456Vocational -> { (emissionsConfig: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions) =>
        emissionsConfig.workdayIdleTimeFraction.class456
      },
      Class78Vocational -> { (emissionsConfig: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions) =>
        emissionsConfig.workdayIdleTimeFraction.class78v
      },
      Class78Tractor -> { (emissionsConfig: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions) =>
        emissionsConfig.workdayIdleTimeFraction.class78t
      }
    )

    val calculationMap: Map[
      EmissionsProcess,
      (
        Emissions,
        BeamVehicle.VehicleActivityData,
        TrieMap[Id[BeamVehicle], Double],
        BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions
      ) => Emissions
    ] = Map(
      /**
        * Calculate Running Exhaust Emissions (RUNEX)
        * VMT by speed bin => gram/veh-mile
        * vmt Vehicle Miles Traveled (VMT)
        * ratesBySpeedBin Emission rate by speed bin (grams per vehicle-mile)
        * @return Total emissions in grams
        */
      RUNEX -> {
        (
          ratesBySpeedBin: Emissions,
          data: BeamVehicle.VehicleActivityData,
          operationTimeMap: TrieMap[Id[BeamVehicle], Double],
          _: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions
        ) =>
          if (!operationTimeMap.contains(data.vehicleId))
            operationTimeMap.put(data.vehicleId, data.activityStartTime)

          val vehicleMilesTraveledInMiles = data.linkLength.map(_ / 1609.344).getOrElse(0.0)

          ratesBySpeedBin * vehicleMilesTraveledInMiles
      },
      /**
        * Calculate Idle Exhaust Emissions (IDLEX)
        * Number of Idle Hours (Parking Hours) => gram/veh-idle hour
        * vih Vehicle Idle Hours (VIH)
        * rates Emission rate (grams per vehicle-idle hour)
        * @return Total emissions in grams
        */
      IDLEX -> {
        (
          rates: Emissions,
          data: BeamVehicle.VehicleActivityData,
          operationTimeMap: TrieMap[Id[BeamVehicle], Double],
          emissionsConfig: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions
        ) =>
          val idlingHours: Double =
            data.parkingActivityType match {

              case Some(Idling) => // Hotelling
                val vehicleParkingInSec = data.parkingDuration.getOrElse(0.0)
                val operationDurationInSec =
                  data.linkStartTime - operationTimeMap.getOrElseUpdate(data.vehicleId, data.activityStartTime)
                operationTimeMap.update(data.vehicleId, data.linkStartTime + vehicleParkingInSec)
                val hotellingInHours = (vehicleParkingInSec + operationDurationInSec) / 3600.0
                hotellingInHours

              case _ =>
                val vehicleDurationInSec = data.linkTravelTime.getOrElse(0.0).max(data.parkingDuration.getOrElse(0.0))
                val operationDurationInSec =
                  data.linkStartTime - operationTimeMap.getOrElseUpdate(data.vehicleId, data.activityStartTime)
                operationTimeMap.update(data.vehicleId, data.linkStartTime + vehicleDurationInSec)
                val workingIdleFactor =
                  workdayIdleFactor.get(data.vehicleType.vehicleCategory).map(_(emissionsConfig)).getOrElse(0.0)
                val portionOfIdlingHours =
                  ((operationDurationInSec + vehicleDurationInSec) / 3600.0) * workingIdleFactor
                portionOfIdlingHours
            }

          rates * idlingHours
      },
      /**
        * Calculate Start Exhaust Emissions (STREX)
        * Number of starts per Soak time => gram/veh-start
        * vst Vehicle Starts (VST)
        * ratesBySoakTime Emission rate by soak time (grams per vehicle-start)
        * @return Total emissions in grams
        */
      // FIXME we might underestimate STREX: Ridehail vehicles do not park, they idle or stop engine while waiting
      STREX -> {
        (
          ratesBySoakTime: Emissions,
          data: BeamVehicle.VehicleActivityData,
          operationTimeMap: TrieMap[Id[BeamVehicle], Double],
          _: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions
        ) =>
          if (!operationTimeMap.contains(data.vehicleId))
            operationTimeMap.put(data.vehicleId, data.activityStartTime)

          val numberOfVehicleStartTimes = 1 // We calculate it for 1 leave parking event

          ratesBySoakTime * numberOfVehicleStartTimes
      },
      /**
        * Calculate Diurnal Evaporative Emissions (DIURN)
        * Cold soak hours (Parking Hours) => gram/veh-hour
        * vph Vehicle Parking Hours (VPH)
        * rates Emission rate (grams per vehicle-hour)
        * @return Total emissions in grams
        */
      // FIXME we might underestimate DIURN: Ridehail vehicles do not park, they idle or stop engine while waiting
      DIURN -> {
        (
          rates: Emissions,
          data: BeamVehicle.VehicleActivityData,
          operationTimeMap: TrieMap[Id[BeamVehicle], Double],
          _: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions
        ) =>
          if (!operationTimeMap.contains(data.vehicleId))
            operationTimeMap.put(data.vehicleId, data.activityStartTime)

          val vehicleParkingInHours = data.parkingDuration.map(_ / 3600.0).getOrElse(0.0)

          rates * vehicleParkingInHours
      },
      /**
        * Calculate Hot Soak Emissions (HOTSOAK)
        * Number of starts => gram/veh-start
        * vst Vehicle Starts (VST)
        * rates Emission rate (grams per vehicle-start)
        * @return Total emissions in grams
        */
      // FIXME we might underestimate HOTSOAK: Ridehail vehicles do not park, idle or stop engine while waiting
      HOTSOAK -> {
        (
          rates: Emissions,
          data: BeamVehicle.VehicleActivityData,
          operationTimeMap: TrieMap[Id[BeamVehicle], Double],
          _: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions
        ) =>
          if (!operationTimeMap.contains(data.vehicleId))
            operationTimeMap.put(data.vehicleId, data.activityStartTime)

          val numberOfVehicleStartTimes = 1 // We calculate it for 1 leave parking event

          rates * numberOfVehicleStartTimes
      },
      /**
        * Calculate Running Loss Evaporative Emissions (RUNLOSS)
        * Running hours (VHT) => gram/veh-hour
        * vht Vehicle Hours Traveled (VHT)
        * rates Emission rate (grams per vehicle-hour)
        * @return Total emissions in grams
        */
      RUNLOSS -> {
        (
          rates: Emissions,
          data: BeamVehicle.VehicleActivityData,
          operationTimeMap: TrieMap[Id[BeamVehicle], Double],
          _: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions
        ) =>
          if (!operationTimeMap.contains(data.vehicleId))
            operationTimeMap.put(data.vehicleId, data.activityStartTime)

          val vehicleHoursTraveledInHours =
            data.linkTravelTime.map(_ / 3600.0).orElse(data.parkingDuration.map(_ / 3600.0)).getOrElse(0.0)

          rates * vehicleHoursTraveledInHours
      },
      /**
        * Calculate Tire Wear Particulate Matter Emissions (PMTW)
        * VMT => gram/veh-mile
        * vmt Vehicle Miles Traveled (VMT)
        * rates Emission rate (grams per vehicle-mile)
        * @return Total emissions in grams
        */
      PMTW -> {
        (
          rates: Emissions,
          data: BeamVehicle.VehicleActivityData,
          operationTimeMap: TrieMap[Id[BeamVehicle], Double],
          _: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions
        ) =>
          if (!operationTimeMap.contains(data.vehicleId))
            operationTimeMap.put(data.vehicleId, data.activityStartTime)

          val vehicleMilesTraveledInMiles = data.linkLength.map(_ / 1609.344).getOrElse(0.0)

          rates * vehicleMilesTraveledInMiles
      },
      /**
        * Calculate Brake Wear Particulate Matter Emissions (PMBW)
        * VMT by speed bin => gram/veh-mile
        * vmt Vehicle Miles Traveled (VMT)
        * ratesBySpeedBin Emission rate by speed bin (grams per vehicle-mile)
        * @return Total emissions in grams
        */
      PMBW -> {
        (
          ratesBySpeedBin: Emissions,
          data: BeamVehicle.VehicleActivityData,
          operationTimeMap: TrieMap[Id[BeamVehicle], Double],
          _: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions
        ) =>
          if (!operationTimeMap.contains(data.vehicleId))
            operationTimeMap.put(data.vehicleId, data.activityStartTime)

          val vehicleMilesTraveledInMiles = data.linkLength.map(_ / 1609.344).getOrElse(0.0)

          ratesBySpeedBin * vehicleMilesTraveledInMiles
      },
      /**
        * Calculate Paved Road Dust Particulate Matter Emissions (PRDUST)
        * VMT => gram/veh-mile
        * vmt Vehicle Miles Traveled (VMT)
        * rates Emission rate (grams per vehicle-mile) without vehicle-weight adjustment.
        * Runtime scaling injects the AP-42 vehicle-weight term W^1.02 using vehicle plus payload weight.
        * @return Total emissions in grams
        */
      PRDUST -> {
        (
          rates: Emissions,
          data: BeamVehicle.VehicleActivityData,
          operationTimeMap: TrieMap[Id[BeamVehicle], Double],
          _: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions
        ) =>
          if (!operationTimeMap.contains(data.vehicleId))
            operationTimeMap.put(data.vehicleId, data.activityStartTime)

          val vehicleMilesTraveledInMiles = data.linkLength.map(_ / 1609.344).getOrElse(0.0)
          val weightInKg = data.vehicleType.curbWeightInKg + data.payloadInKg.getOrElse(0.0)
          val shortTonsPerKg = 1.0 / 907.18474
          val weightInShortTons = weightInKg * shortTonsPerKg
          val prdustWeightMultiplier = math.pow(weightInShortTons, 1.02)

          rates * (vehicleMilesTraveledInMiles * prdustWeightMultiplier)
      },
      /**
        * Calculate Power Take-Off Exhaust Emissions (PTOEX)
        * PTOEX is treated as a traversal exhaust process using the same speed-bin lookup structure as RUNEX.
        * Rates are applied per mile traveled.
        */
      PTOEX -> {
        (
          ratesBySpeedBin: Emissions,
          data: BeamVehicle.VehicleActivityData,
          operationTimeMap: TrieMap[Id[BeamVehicle], Double],
          _: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions
        ) =>
          if (!operationTimeMap.contains(data.vehicleId))
            operationTimeMap.put(data.vehicleId, data.activityStartTime)

          val vehicleMilesTraveledInMiles = data.linkLength.map(_ / 1609.344).getOrElse(0.0)

          ratesBySpeedBin * vehicleMilesTraveledInMiles
      }
    )
  }

  private class EmissionsRateFilterStore(
    baseFilePaths: IndexedSeq[String],
    emissionsRateFilePathsByVehicleType: IndexedSeq[(BeamVehicleType, Option[String])]
  ) {
    private lazy val log = LoggerFactory.getLogger(this.getClass)

    private val emissionRateFiltersByVehicleType
      : Map[BeamVehicleType, Future[EmissionsRateFilterStore.EmissionsRateFilter]] =
      beginLoadingEmissionRateFiltersFor(emissionsRateFilePathsByVehicleType)

    def getEmissionsRateFilterFor(
      vehicleType: BeamVehicleType
    ): Option[Future[EmissionsRateFilterStore.EmissionsRateFilter]] = emissionRateFiltersByVehicleType.get(vehicleType)

    private def beginLoadingEmissionRateFiltersFor(
      files: IndexedSeq[(BeamVehicleType, Option[String])]
    ): Map[BeamVehicleType, Future[EmissionsRateFilterStore.EmissionsRateFilter]] = {
      files.collect {
        case (vehicleType, Some(filePath)) if filePath.trim.nonEmpty =>
          val consumptionFuture = Future {
            //Do NOT move this out - sharing the parser between threads is questionable
            val settings = new CsvParserSettings()
            settings.setHeaderExtractionEnabled(true)
            settings.detectFormatAutomatically()
            val csvParser = new CsvParser(settings)
            EmissionsRateTableLoader.loadFromFile(baseFilePaths, filePath, csvParser)
          }
          consumptionFuture.failed.map(ex => log.error(s"Error while loading emission rate filter", ex))
          vehicleType -> consumptionFuture
      }.toMap
    }
  }

  private[vehicles] object EmissionsRateTableLoader {
    private val activityBinHeader = "speedMph_timeMin"
    private val countyHeaders = Seq("county")
    private val roadCategoryHeaders = Seq("roadCategory")
    private val emissionsProcessHeaders = Seq("process")

    private val rateCH4Headers = Seq("ch4_gram")
    private val rateCOHeaders = Seq("co_gram")
    private val rateCO2Headers = Seq("co2_gram")
    private val rateHCHeaders = Seq("hc_gram")
    private val rateNH3Headers = Seq("nh3_gram")
    private val rateN2OHeaders = Seq("n2o_gram")
    private val rateNOxHeaders = Seq("nox_gram")
    private val ratePMHeaders = Seq("pm_gram")
    private val ratePM10Headers = Seq("pm10_gram")
    private val ratePM25Headers = Seq("pm25_gram")
    private val rateROGHeaders = Seq("rog_gram")
    private val rateSOxHeaders = Seq("sox_gram")
    private val rateTOGHeaders = Seq("tog_gram")
    private val rateBCHeaders = Seq("bc_gram")

    private sealed trait EmissionsRateRow {
      def getString(header: String): Option[String]
      def getDouble(header: String): Option[Double]
      def debugString: String

      def getStringAny(headers: Seq[String]): Option[String] = headers.iterator.flatMap(getString).toSeq.headOption
      def getDoubleAny(headers: Seq[String]): Option[Double] = headers.iterator.flatMap(getDouble).toSeq.headOption
    }

    private case class CsvEmissionsRateRow(record: Record) extends EmissionsRateRow {

      override def getString(header: String): Option[String] =
        if (!record.getMetaData.containsColumn(header)) None
        else Option(record.getString(header)).map(_.trim).filter(_.nonEmpty)

      override def getDouble(header: String): Option[Double] =
        if (!record.getMetaData.containsColumn(header)) None
        else Option(record.getString(header)).map(_.trim).filter(_.nonEmpty).map(_.toDouble)

      override def debugString: String = record.toString
    }

    private def processUsesCombinedSpeedBin(process: String): Boolean =
      Set(
        EmissionsProfile.RUNEX.toString,
        EmissionsProfile.PTOEX.toString,
        EmissionsProfile.PMBW.toString,
        EmissionsProfile.PMTW.toString,
        EmissionsProfile.PRDUST.toString
      ).contains(process)

    private def processUsesCombinedTimeBin(process: String): Boolean =
      Set(
        EmissionsProfile.STREX.toString,
        EmissionsProfile.DIURN.toString,
        EmissionsProfile.HOTSOAK.toString,
        EmissionsProfile.RUNLOSS.toString
      ).contains(process)

    private def toBin(value: String, halfWidth: Double = 0.0): DoubleTypedRange = {
      val trimmed = value.trim
      if (trimmed.startsWith("[") || trimmed.startsWith("(")) {
        convertRecordStringToDoubleTypedRange(trimmed)
      } else {
        val center = trimmed.toDouble
        convertRecordStringToDoubleTypedRange(s"[${center - halfWidth},${center + halfWidth}]")
      }
    }

    private def getSpeedBin(row: EmissionsRateRow, emissionProcess: String): DoubleTypedRange = {
      row.getString(activityBinHeader) match {
        case Some(value) if value.nonEmpty && processUsesCombinedSpeedBin(emissionProcess) =>
          toBin(value, halfWidth = 2.5)
        case _ =>
          convertRecordStringToDoubleTypedRange("[0,200]")
      }
    }

    private def getSoakTimeBin(row: EmissionsRateRow, emissionProcess: String): DoubleTypedRange = {
      row.getString(activityBinHeader) match {
        case Some(value) if value.nonEmpty && processUsesCombinedTimeBin(emissionProcess) =>
          toBin(value, halfWidth = 0.5)
        case _ =>
          convertRecordStringToDoubleTypedRange("[0,216000]")
      }
    }

    private def getActivityBin(row: EmissionsRateRow, emissionProcess: String): DoubleTypedRange = {
      if (processUsesCombinedTimeBin(emissionProcess)) getSoakTimeBin(row, emissionProcess)
      else getSpeedBin(row, emissionProcess)
    }

    private case class ParquetEmissionsRateRow(record: GenericRecord) extends EmissionsRateRow {

      override def getString(header: String): Option[String] =
        try {
          Option(record.get(header)).map(_.toString.trim).filter(_.nonEmpty)
        } catch {
          case _: AvroRuntimeException => None
        }

      override def getDouble(header: String): Option[Double] = {
        try {
          Option(record.get(header)).map {
            case n: java.lang.Number => n.doubleValue()
            case other               => other.toString.toDouble
          }
        } catch {
          case _: AvroRuntimeException => None
        }
      }

      override def debugString: String = record.toString
    }

    def loadFromFile(
      baseFilePaths: IndexedSeq[String],
      file: String,
      csvParser: CsvParser
    ): EmissionsRateFilterStore.EmissionsRateFilter = {
      val currentRateFilter =
        mutable.Map.empty[String, mutable.Map[String, mutable.Map[String, mutable.Map[DoubleTypedRange, Emissions]]]]

      var rowCount = 0
      logger.info(s"Loading emission rates from file: $file")

      resolveFilePaths(baseFilePaths, file).foreach { resolvedPath =>
        getVehicleEmissionsRows(csvParser, resolvedPath).foreach { row =>
          rowCount += 1

          val emissionProcess =
            EmissionsProfile
              .fromString(row.getStringAny(emissionsProcessHeaders).getOrElse(""))
              .map(_.toString)
              .getOrElse("")
          val activityBin = getActivityBin(row, emissionProcess)
          val county = row.getStringAny(countyHeaders).getOrElse("")
          val roadCategory = row.getStringAny(roadCategoryHeaders).getOrElse("")

          val ratesInGramsPerMile = Emissions(
            List(
              Emissions.CH4  -> row.getDoubleAny(rateCH4Headers).getOrElse(0.0),
              Emissions.CO   -> row.getDoubleAny(rateCOHeaders).getOrElse(0.0),
              Emissions.CO2  -> row.getDoubleAny(rateCO2Headers).getOrElse(0.0),
              Emissions.HC   -> row.getDoubleAny(rateHCHeaders).getOrElse(0.0),
              Emissions.NH3  -> row.getDoubleAny(rateNH3Headers).getOrElse(0.0),
              Emissions.N2O  -> row.getDoubleAny(rateN2OHeaders).getOrElse(0.0),
              Emissions.NOx  -> row.getDoubleAny(rateNOxHeaders).getOrElse(0.0),
              Emissions.PM   -> row.getDoubleAny(ratePMHeaders).getOrElse(0.0),
              Emissions.PM10 -> row.getDoubleAny(ratePM10Headers).getOrElse(0.0),
              Emissions.PM25 -> row.getDoubleAny(ratePM25Headers).getOrElse(0.0),
              Emissions.ROG  -> row.getDoubleAny(rateROGHeaders).getOrElse(0.0),
              Emissions.SOx  -> row.getDoubleAny(rateSOxHeaders).getOrElse(0.0),
              Emissions.TOG  -> row.getDoubleAny(rateTOGHeaders).getOrElse(0.0),
              Emissions.BC   -> row.getDoubleAny(rateBCHeaders).getOrElse(0.0)
            ).filter(_._2 != 0.0): _*
          )
          if (ratesInGramsPerMile.notValid) {
            logger.error(
              s"Record ${row.debugString} does not contain a valid rate. Erroring early to bring attention and get it fixed."
            )
          }

          val processFilter = currentRateFilter.getOrElseUpdate(county, mutable.Map.empty)
          val roadCategoryFilter = processFilter.getOrElseUpdate(emissionProcess, mutable.Map.empty)
          val activityFilter = roadCategoryFilter.getOrElseUpdate(roadCategory, mutable.Map.empty)

          activityFilter.get(activityBin) match {
            case Some(existingRates) =>
              val overlappingPollutants =
                existingRates.values.keySet.intersect(ratesInGramsPerMile.values.keySet)
              val conflictingPollutants = overlappingPollutants.filter { pollutant =>
                existingRates.values.getOrElse(pollutant, 0.0) != ratesInGramsPerMile.values.getOrElse(pollutant, 0.0)
              }
              if (conflictingPollutants.nonEmpty) {
                logger.warn(
                  "Two emission rates found for the same key combination: County = {}; Process = {}; Road Category = {}; Activity Bin = {}. " +
                  s"Merging rates, but found conflicting values for pollutants ${conflictingPollutants.mkString(", ")}. " +
                  s"Existing rate: $existingRates. New rate: $ratesInGramsPerMile.",
                  county,
                  emissionProcess,
                  roadCategory,
                  activityBin
                )
              }
              activityFilter += activityBin -> (existingRates + ratesInGramsPerMile)
            case None =>
              activityFilter += activityBin -> ratesInGramsPerMile
          }
        }
      }

      logger.info(s"Finished loading emission rates. Total number of emissions entries: $rowCount")

      currentRateFilter.toMap.map { case (county, processMap) =>
        county -> processMap.toMap.map { case (emissionProcess, roadCategoryMap) =>
          emissionProcess -> roadCategoryMap.toMap.map { case (roadCategory, activityBinMap) =>
            roadCategory -> activityBinMap.toMap
          }
        }
      }
    }

    private def resolveFilePaths(baseFilePaths: IndexedSeq[String], file: String): IndexedSeq[String] = {
      if (baseFilePaths.isEmpty) {
        IndexedSeq(file)
      } else {
        baseFilePaths.map(baseFilePath => Paths.get(baseFilePath, file).toString)
      }
    }

    private def getVehicleEmissionsRows(csvParser: CsvParser, filePath: String): Iterable[EmissionsRateRow] = {
      if (filePath.toLowerCase.endsWith(".parquet")) {
        val (iter, toClose) = ParquetReader.read(filePath)
        new Iterable[EmissionsRateRow] {
          override def iterator(): Iterator[EmissionsRateRow] =
            new Iterator[EmissionsRateRow] {
              private val delegate = iter.map(ParquetEmissionsRateRow)
              private var closed = false

              override def hasNext: Boolean = {
                val hasNextValue = delegate.hasNext
                if (!hasNextValue && !closed) {
                  closed = true
                  toClose.close()
                }
                hasNextValue
              }

              override def next(): EmissionsRateRow = delegate.next()
            }
        }
      } else {
        csvParser
          .iterateRecords(IOUtils.getBufferedReader(filePath))
          .asScala
          .map(CsvEmissionsRateRow)
      }
    }
  }
}
