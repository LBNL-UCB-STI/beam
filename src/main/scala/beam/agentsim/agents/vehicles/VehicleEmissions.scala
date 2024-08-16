package beam.agentsim.agents.vehicles

import beam.agentsim.agents.freight.FreightRequestType
import beam.agentsim.agents.vehicles.VehicleEmissions.Emissions.{formatName, EmissionType}
import beam.agentsim.agents.vehicles.VehicleEmissions.EmissionsProfile.EmissionsProcess
import beam.agentsim.events.{LeavingParkingEvent, PathTraversalEvent}
import beam.router.skim.event.EmissionsSkimmerEvent
import beam.sim.BeamServices
import beam.sim.common.DoubleTypedRange
import beam.utils.BeamVehicleUtils
import beam.utils.BeamVehicleUtils.convertRecordStringToDoubleTypedRange
import com.typesafe.scalalogging.LazyLogging
import com.univocity.parsers.common.record.Record
import com.univocity.parsers.csv.{CsvParser, CsvParserSettings}
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.io.IOUtils
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Try

class VehicleEmissions(
  vehicleTypesBasePaths: IndexedSeq[String],
  vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType],
  linkToGradePercentFilePath: String,
  pollutantsToFilterOut: List[String]
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

  private lazy val linkIdToGradePercentMap =
    BeamVehicleUtils.loadLinkIdToGradeMapFromCSV(csvParser, linkToGradePercentFilePath)

  Emissions.setFilter(pollutantsToFilterOut)

  def getEmissionsProfileInGram(
    vehicleActivityData: IndexedSeq[BeamVehicle.VehicleActivityData],
    vehicleActivity: Class[_ <: org.matsim.api.core.v01.events.Event],
    vehicleType: BeamVehicleType,
    beamServices: BeamServices
  ): Option[EmissionsProfile] = {
    val emissionsProfiles = for {
      process                    <- identifyProcesses(vehicleActivityData, vehicleActivity)
      data                       <- vehicleActivityData
      emissionsRatesFilterFuture <- emissionsRatesFilterStore.getEmissionsRateFilterFor(data.vehicleType)
      emissionsRatesFilter = Await.result(emissionsRatesFilterFuture, 1.minute)
      fallBack = vehicleType.emissionsRatesInGramsPerMile
      rates <- getRatesUsing(emissionsRatesFilter, data, process).orElse(fallBack.flatMap(_.values.get(process)))
    } yield {
      val emissions = calculationMap(process)(rates, data)
      if (beamServices.beamConfig.beam.exchange.output.emissions.skims) {
        // Create and process EmissionsSkimmerEvent
        beamServices.matsimServices.getEvents.processEvent(
          EmissionsSkimmerEvent(
            time = data.time,
            linkId = data.linkId,
            zone = data.taz.map(_.tazId.toString).getOrElse(""),
            vehicleType = vehicleType.id.toString,
            emissions = emissions,
            emissionsProcess = process,
            travelTime = data.linkTravelTime.getOrElse(0.0),
            parkingDuration = data.parkingDuration.getOrElse(0.0),
            energyConsumption = data.primaryEnergyConsumed + data.secondaryEnergyConsumed,
            beamServices = beamServices
          )
        )
      }
      process -> emissions
    }

    if (emissionsProfiles.isEmpty) None else Some(EmissionsProfile(emissionsProfiles.toMap))
  }

  private def findInterval[T](
    map: Map[DoubleTypedRange, T],
    value: Double,
    getDefaultInterval: Boolean = true
  ): Option[(DoubleTypedRange, T)] = {
    val filteredMap = map.filter(_._1.has(value))
    if (filteredMap.isEmpty) {
      None
    } else {
      Some(filteredMap.maxBy { case (range, _) =>
        (range.upperBound - range.lowerBound) * (if (getDefaultInterval) 1 else -1)
      })
    }
  }

  private def getRatesUsing(
    emissionRateFilter: EmissionsRateFilterStore.EmissionsRateFilter,
    data: BeamVehicle.VehicleActivityData,
    process: EmissionsProcess
  ): Option[Emissions] = {
    val speedInMilesPerHour =
      data.averageSpeed.map(BeamVehicleUtils.convertFromMetersPerSecondToMilesPerHour).getOrElse(0.0)
    val weightKg = data.vehicleType.curbWeightInKg + data.payloadInKg.getOrElse(0.0)
    val soakTimeIntMinutes = data.parkingDuration.map(_ / 60.0).getOrElse(0.0)
    val gradePercent = linkIdToGradePercentMap.getOrElse(data.linkId, 0.0)
    val county = data.taz.flatMap(_.county).getOrElse("")

    for {
      (_, gradeFilter) <- findInterval(
        emissionRateFilter,
        speedInMilesPerHour,
        !List(RUNEX, PMBW).contains(process)
      )
      (_, weightFilter)   <- findInterval(gradeFilter, gradePercent)
      (_, soakTimeFilter) <- findInterval(weightFilter, weightKg)
      (_, countyFilter) <- findInterval(
        soakTimeFilter,
        soakTimeIntMinutes,
        !List(STREX).contains(process)
      )
      (_, processFilter) <- countyFilter.find(_._1 == county.trim.toLowerCase)
      rate               <- processFilter.get(process.toString)
    } yield rate
  }
}

object VehicleEmissions extends LazyLogging {

  object EmissionsRateFilterStore {

    // speed -> (gradePercent -> (weight -> (soakTime -> (county -> (emissionProcess -> rate)))))
    type EmissionsRateFilter = Map[
      DoubleTypedRange, // speed
      Map[
        DoubleTypedRange, // grade percent
        Map[
          DoubleTypedRange, // weight
          Map[
            DoubleTypedRange, // soak time
            Map[
              String, // county
              Map[
                String, // emissionProcess
                Emissions // rate
              ]
            ]
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

    def get(emissionType: EmissionType): Option[Double] = values.get(emissionType)

    override def toString: String =
      values.map { case (key, value) => s"${formatName(key)}=$value" }.mkString("Emissions(", ", ", ")")
  }

  object Emissions extends Enumeration {
    type EmissionType = Value
    val CH4, CO, CO2, HC, NH3, NOx, PM, PM10, PM2_5, ROG, SOx, TOG = Value

    var filter: Option[List[EmissionType]] = None

    def setFilter(emissionsStr: List[String]): Unit = {
      if (filter.isEmpty) {
        filter = Some(emissionsStr.flatMap(fromString))
        if (filter.exists(_.nonEmpty)) {
          val to_filter_out = filter.map(_.map(_.toString).mkString(", ")).getOrElse("").trim
          logger.info(s"Filtering out the following pollutants (see emissions.pollutantsToFilterOut): $to_filter_out")
        }
      }
    }

    def formatName(emissionType: EmissionType): String = emissionType match {
      case PM2_5 => "PM2_5"
      case _     => emissionType.toString
    }

    def fromString(s: String): Option[EmissionType] = {
      values.find(v => formatName(v).equalsIgnoreCase(s))
    }

    def init(): Emissions = Emissions()

    def apply(values: (EmissionType, Double)*): Emissions = {
      new Emissions(values.filter(v => !filter.contains(v._1)).toMap)
    }

    def formatEmissions(emissions: Emissions): String =
      emissions.values.map { case (key, value) => formatEmission(formatName(key), value) }.mkString(", ")

    private def formatEmission(name: String, value: Double): String = f"$name: $value%.2f"
  }

  case class EmissionsProfile(values: Map[EmissionsProcess, Emissions] = Map.empty) {}

  object EmissionsProfile extends Enumeration {
    type EmissionsProcess = Value
    val RUNEX, IDLEX, STREX, HOTSOAK, DIURN, RUNLOSS, PMTW, PMBW = Value

    def init(): EmissionsProfile = EmissionsProfile()

    def apply(values: (EmissionsProcess, Emissions)*): EmissionsProfile = new EmissionsProfile(values.toMap)

    def identifyProcesses(
      data: IndexedSeq[BeamVehicle.VehicleActivityData],
      vehicleActivity: Class[_ <: org.matsim.api.core.v01.events.Event]
    ): ValueSet = {
      // TODO uncomment once commercial deployment are deployed
      if (data.isEmpty)
        return ValueSet.empty
      EmissionsProfile.values.flatMap {
        case process @ IDLEX if vehicleActivity == classOf[LeavingParkingEvent] =>
          // TODO In the future we will need to look at whether vehicle is hotelling
          // If vehicle is loading or unloading
          if (data.head.activityType.exists(_.toLowerCase.contains(FreightRequestType.Loading.toString.toLowerCase)))
            Some(process)
          else None
        case process @ (STREX | DIURN | HOTSOAK | RUNLOSS) if vehicleActivity == classOf[LeavingParkingEvent] =>
          Some(process)
        case process @ (RUNEX | PMBW | PMTW | RUNLOSS) if vehicleActivity == classOf[PathTraversalEvent] =>
          Some(process)
        case _ => None
      }
    }

    def fromString(process: String): Option[EmissionsProcess] = process.toLowerCase match {
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
      case "hotelling" | "idle" | "idlex" => Some(IDLEX)

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

      // if process is not recognized then RUNEX emission will be used
      case _ =>
        logger.warn(s"Unrecognized emission process: $process")
        None
    }

    val calculationMap: Map[EmissionsProcess, (Emissions, BeamVehicle.VehicleActivityData) => Emissions] = Map(
      /**
        * Calculate Running Exhaust Emissions (RUNEX)
        * VMT by speed bin => gram/veh-mile
        * vmt Vehicle Miles Traveled (VMT)
        * ratesBySpeedBin Emission rate by speed bin (grams per vehicle-mile)
        * @return Total emissions in grams
        */
      RUNEX -> { (ratesBySpeedBin: Emissions, data: BeamVehicle.VehicleActivityData) =>
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
      IDLEX -> { (rates: Emissions, data: BeamVehicle.VehicleActivityData) =>
        val vehicleIdleInHours = data.parkingDuration.map(_ / 3600.0).getOrElse(0.0)
        rates * vehicleIdleInHours
      },
      /**
        * Calculate Start Exhaust Emissions (STREX)
        * Number of starts per Soak time => gram/veh-start
        * vst Vehicle Starts (VST)
        * ratesBySoakTime Emission rate by soak time (grams per vehicle-start)
        * @return Total emissions in grams
        */
      // FIXME we might underestimate STREX: Ridehail vehicles do not park, they idle or stop engine while waiting
      STREX -> { (ratesBySoakTime: Emissions, _: BeamVehicle.VehicleActivityData) =>
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
      DIURN -> { (rates: Emissions, data: BeamVehicle.VehicleActivityData) =>
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
      HOTSOAK -> { (rates: Emissions, _: BeamVehicle.VehicleActivityData) =>
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
      RUNLOSS -> { (rates: Emissions, data: BeamVehicle.VehicleActivityData) =>
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
      PMTW -> { (rates: Emissions, data: BeamVehicle.VehicleActivityData) =>
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
      PMBW -> { (ratesBySpeedBin: Emissions, data: BeamVehicle.VehicleActivityData) =>
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
    //Hard-coding can become configurable if necessary
    private val speedBinHeader = "speed_mph_float_bins"
    private val gradeBinHeader = "grade_percent_float_bins"
    private val weightBinHeader = "mass_kg_float_bins"
    private val soakTimeBinHeader = "time_minutes_float_bins"
    private val countyBinHeader = "county"
    /*
    Emissions Processes:
    RUNEX - Running Exhaust: Emissions from vehicle tailpipe while traveling on the road
    IDLEX - Idle Exhaust: Emissions from vehicle tailpipe while operating but not traveling (e.g., heavy-duty trucks during loading/unloading)
    STREX - Start Exhaust: Emissions occurring when starting a vehicle, independent of running exhaust
    DIURN - Diurnal Evaporative: Emissions from fuel evaporation due to daily temperature changes while the vehicle is not operating
    HOTSOAK - Hot Soak Evaporative: Emissions from fuel evaporation immediately after a vehicle is turned off
    RUNLOSS - Running Loss Evaporative: Emissions from fuel evaporation while the vehicle is operating
    PMTW - Particulate Matter Tire Wear: Emissions from tire wear during vehicle operation
    PMBW - Particulate Matter Brake Wear: Emissions from brake wear during vehicle operation

    All emission rates (rate_XXX) are in grams per mile, except for:
    - IDLEX: grams per hour
    - STREX: grams per start
    - DIURN and HOTSOAK: grams per vehicle per day
     */
    private val emissionsProcessHeader = "process"

    /*
     * rateXXX is in grams per mile
     * */
    private val rateCH4Header = "rate_ch4_gram_float"
    private val rateCOHeader = "rate_co_gram_float"
    private val rateCO2Header = "rate_co2_gram_float"
    private val rateHCHeader = "rate_hc_gram_float"
    private val rateNH3Header = "rate_nh3_gram_float"
    private val rateNOxHeader = "rate_nox_gram_float"
    private val ratePMHeader = "rate_pm_gram_float"
    private val ratePM10Header = "rate_pm10_gram_float"
    private val ratePM2_5Header = "rate_pm2_5_gram_float"
    private val rateROGHeader = "rate_rog_gram_float"
    private val rateSOxHeader = "rate_sox_gram_float"
    private val rateTOGHeader = "rate_tog_gram_float"

    private val emissionRateFiltersByVehicleType
      : Map[BeamVehicleType, Future[EmissionsRateFilterStore.EmissionsRateFilter]] =
      beginLoadingEmissionRateFiltersFor(emissionsRateFilePathsByVehicleType)

    def getEmissionsRateFilterFor(
      vehicleType: BeamVehicleType
    ): Option[Future[EmissionsRateFilterStore.EmissionsRateFilter]] = emissionRateFiltersByVehicleType.get(vehicleType)

    private def getVehicleEmissionsRecordsUsing(csvParser: CsvParser, filePath: String): Iterable[Record] = {
      csvParser.iterateRecords(IOUtils.getBufferedReader(filePath)).asScala
    }

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
            loadEmissionRatesFromCSVFor(filePath, csvParser)
          }
          consumptionFuture.failed.map(ex => log.error(s"Error while loading emission rate filter", ex))
          vehicleType -> consumptionFuture
      }.toMap
    }

    private def getString(csvRecord: Record, header: String, default: String): String = {
      if (!csvRecord.getMetaData.containsColumn(header)) default
      else Option(csvRecord.getString(header)).filterNot(_.isEmpty).getOrElse(default)
    }

    private def loadEmissionRatesFromCSVFor(
      file: String,
      csvParser: CsvParser
    ): EmissionsRateFilterStore.EmissionsRateFilter = {

      val currentRateFilter = mutable.Map.empty[DoubleTypedRange, mutable.Map[DoubleTypedRange, mutable.Map[
        DoubleTypedRange,
        mutable.Map[DoubleTypedRange, mutable.Map[String, mutable.Map[String, Emissions]]]
      ]]]

      var rowCount = 0
      log.info(s"Loading emission rates from file: $file")

      baseFilePaths.foreach(baseFilePath =>
        getVehicleEmissionsRecordsUsing(csvParser, java.nio.file.Paths.get(baseFilePath, file).toString)
          .foreach(csvRecord => {
            rowCount += 1

            // Speed Bin in MPH
            val speedInMilesPerHourBin: DoubleTypedRange =
              convertRecordStringToDoubleTypedRange(getString(csvRecord, speedBinHeader, "[0,200]"))
            // Road Grade Bin in PERCENTAGE
            val gradePercentBin: DoubleTypedRange =
              convertRecordStringToDoubleTypedRange(getString(csvRecord, gradeBinHeader, "[-100,100]"))
            // Weight in Kg
            val weightKgBin: DoubleTypedRange =
              convertRecordStringToDoubleTypedRange(getString(csvRecord, weightBinHeader, "[0,200000]"))
            // Soak Time in minutes
            val soakTimeBin: DoubleTypedRange =
              convertRecordStringToDoubleTypedRange(getString(csvRecord, soakTimeBinHeader, "[0,216000]"))
            // Geographic area, None if not defined
            val county: String = getString(csvRecord, countyBinHeader, "")
            // Emission process
            val emissionProcess: String =
              EmissionsProfile
                .fromString(getString(csvRecord, emissionsProcessHeader, ""))
                .map(_.toString)
                .getOrElse("")

            def readRateCheckIfNull(headerName: String): Double = {
              val value = csvRecord.getDouble(headerName)
              if (value == null) {
                log.warn(
                  s"Record $csvRecord does not contain a valid rate for $headerName. " +
                  "Warning early to bring attention and get it fixed if not intended."
                )
                0.0
              } else value
            }

            // Emissions Rates in Grans Per Mile
            val ratesInGramsPerMile = Emissions(
              List(
                Emissions.CH4   -> readRateCheckIfNull(rateCH4Header),
                Emissions.CO    -> readRateCheckIfNull(rateCOHeader),
                Emissions.CO2   -> readRateCheckIfNull(rateCO2Header),
                Emissions.HC    -> readRateCheckIfNull(rateHCHeader),
                Emissions.NH3   -> readRateCheckIfNull(rateNH3Header),
                Emissions.NOx   -> readRateCheckIfNull(rateNOxHeader),
                Emissions.PM    -> readRateCheckIfNull(ratePMHeader),
                Emissions.PM10  -> readRateCheckIfNull(ratePM10Header),
                Emissions.PM2_5 -> readRateCheckIfNull(ratePM2_5Header),
                Emissions.ROG   -> readRateCheckIfNull(rateROGHeader),
                Emissions.SOx   -> readRateCheckIfNull(rateSOxHeader),
                Emissions.TOG   -> readRateCheckIfNull(rateTOGHeader)
              ).filter(_._2 != 0.0): _*
            )
            if (ratesInGramsPerMile.notValid) {
              log.error(
                s"Record $csvRecord does not contain a valid rate. " +
                "Erroring early to bring attention and get it fixed."
              )
            }

            currentRateFilter.get(speedInMilesPerHourBin) match {
              case Some(gradePercentFilter) =>
                gradePercentFilter.get(gradePercentBin) match {
                  case Some(weightKgFilter) =>
                    weightKgFilter.get(weightKgBin) match {
                      case Some(soakTimeFilter) =>
                        soakTimeFilter.get(soakTimeBin) match {
                          case Some(countyFilter) =>
                            countyFilter.get(county) match {
                              case Some(emissionsProcessFilter) =>
                                emissionsProcessFilter.get(emissionProcess) match {
                                  case Some(existingRates) =>
                                    log.error(
                                      "Two emission rates found for the same bin combination: " +
                                      "County = {}; Speed In Miles Per Hour Bin = {}; " +
                                      "Grade Percent Bin = {}; Weight kg Bin = {}; Soak Time Bin = {}. " +
                                      s"Keeping first rate of $existingRates and ignoring new rate of $ratesInGramsPerMile.",
                                      county,
                                      speedInMilesPerHourBin,
                                      gradePercentBin,
                                      weightKgBin,
                                      soakTimeBin
                                    )
                                  case None =>
                                    emissionsProcessFilter += emissionProcess -> ratesInGramsPerMile
                                }
                              case None =>
                                countyFilter += county -> mutable.Map(emissionProcess -> ratesInGramsPerMile)
                            }
                          case None =>
                            soakTimeFilter += soakTimeBin -> mutable.Map(
                              county -> mutable.Map(emissionProcess -> ratesInGramsPerMile)
                            )
                        }
                      case None =>
                        weightKgFilter += weightKgBin -> mutable.Map(
                          soakTimeBin -> mutable.Map(
                            county -> mutable.Map(emissionProcess -> ratesInGramsPerMile)
                          )
                        )
                    }
                  case None =>
                    gradePercentFilter += gradePercentBin -> mutable.Map(
                      weightKgBin -> mutable.Map(
                        soakTimeBin -> mutable.Map(
                          county -> mutable.Map(emissionProcess -> ratesInGramsPerMile)
                        )
                      )
                    )
                }
              case None =>
                currentRateFilter += speedInMilesPerHourBin -> mutable.Map(
                  gradePercentBin -> mutable.Map(
                    weightKgBin -> mutable.Map(
                      soakTimeBin -> mutable.Map(
                        county -> mutable.Map(emissionProcess -> ratesInGramsPerMile)
                      )
                    )
                  )
                )
            }
          })
      )

      log.info(s"Finished loading emission rates. Total number of emissions entries: $rowCount")

      currentRateFilter.toMap.map { case (speedInMilesPerHourBin, gradePercentMap) =>
        speedInMilesPerHourBin -> gradePercentMap.toMap.map { case (gradePercentBin, weightMap) =>
          gradePercentBin -> weightMap.toMap.map { case (weightKgBin, soakTimeMap) =>
            weightKgBin -> soakTimeMap.toMap.map { case (soakTimeBin, countyMap) =>
              soakTimeBin -> countyMap.toMap.map { case (county, emissionsProcessMap) =>
                county -> emissionsProcessMap.toMap
              }
            }
          }
        }
      }
    }
  }
}
