package beam.agentsim.agents.vehicles

import beam.agentsim.events.{LeavingParkingEvent, PathTraversalEvent}
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.common.DoubleTypedRange
import beam.utils.BeamVehicleUtils
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

class VehicleEmissions(
  vehicleTypesBasePaths: IndexedSeq[String],
  vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType],
  linkToGradePercentFilePath: String,
  embedEmissionsProfiles: Boolean
) {
  import VehicleEmissions._
  import EmissionsProcesses._
  import EmissionsProfile._
  private val settings = new CsvParserSettings()
  settings.setHeaderExtractionEnabled(true)
  settings.detectFormatAutomatically()
  private val csvParser = new CsvParser(settings)

  private lazy val emissionsRatesFilterStore = new VehicleEmissions.EmissionsRateFilterStore(
    vehicleTypesBasePaths,
    emissionsRateFilePathsByVehicleType = vehicleTypes.values.map(x => (x, x.emissionsRatesFile)).toIndexedSeq
  )

  private lazy val linkIdToGradePercentMap =
    BeamVehicleUtils.loadLinkIdToGradeMapFromCSV(csvParser, linkToGradePercentFilePath)

  private lazy val truckCategory =
    List(VehicleCategory.LightHeavyDutyTruck, VehicleCategory.MediumHeavyDutyTruck, VehicleCategory.HeavyHeavyDutyTruck)

  private lazy val soakProcesses = List(STREX, DIURN, HOTSOAK, RUNLOSS)
  private lazy val hotellingProcesses = List(IDLEX)
  private lazy val runProcesses = List(RUNEX, PMBW, PMTW, RUNLOSS)

  def getEmissionsProfileInGram(
    vehicleActivityData: IndexedSeq[BeamVehicle.VehicleActivityData],
    vehicleActivity: Class[_ <: org.matsim.api.core.v01.events.Event],
    vehicleType: BeamVehicleType
  ): Option[EmissionsProfile] = {
    if (!embedEmissionsProfiles) return None
    val isTruck = truckCategory.contains(vehicleType.vehicleCategory)
    val processs = Map[Class[_ <: org.matsim.api.core.v01.events.Event], List[EmissionsProcess]](
      classOf[LeavingParkingEvent] -> (if (isTruck) soakProcesses ++ hotellingProcesses else soakProcesses),
      classOf[PathTraversalEvent]  -> runProcesses
    ).getOrElse(vehicleActivity, List.empty)
    val emissionsProfiles = for {
      process                    <- processs
      data                       <- vehicleActivityData
      emissionsRatesFilterFuture <- emissionsRatesFilterStore.getEmissionsRateFilterFor(data.vehicleType)
      emissionsRatesFilter = Await.result(emissionsRatesFilterFuture, 1.minute)
      fallBack = vehicleType.emissionsRatesInGramsPerMile
      rates <- getRatesUsing(emissionsRatesFilter, data, process).orElse(fallBack.flatMap(_.get(process)))
    } yield process -> calculationMap(process)(rates, data)
    if (emissionsProfiles.isEmpty) None else Some(emissionsProfiles.toMap)
  }

  private def getRatesUsing(
    emissionRateFilter: EmissionsRateFilterStore.EmissionsRateFilter,
    data: BeamVehicle.VehicleActivityData,
    process: EmissionsProcesses.EmissionsProcess
  ): Option[Emissions] = {
    val speedInMilesPerHour: Double = data.averageSpeed
      .map(BeamVehicleUtils.convertFromMetersPerSecondToMilesPerHour)
      .getOrElse(0)
    val weightKg: Double = data.vehicleType.curbWeightInKg + data.payloadInKg.getOrElse(0.0)
    val soakTimeIntMinutes: Int = data.parkingDuration.map(_ / 60.0).getOrElse(0.0).toInt
    val gradePercent: Double = linkIdToGradePercentMap.getOrElse(data.linkId, 0)
    val zone = data.tazId.getOrElse("")
    for {
      (_, gradeFilter) <- emissionRateFilter.find { case (speedInMilesPerHourBin, _) =>
        speedInMilesPerHourBin.has(speedInMilesPerHour)
      }
      (_, weightFilter)   <- gradeFilter.find { case (gradePercentBin, _) => gradePercentBin.has(gradePercent) }
      (_, soakTimeFilter) <- weightFilter.find { case (weightPercentBin, _) => weightPercentBin.has(weightKg) }
      (_, tazFilter)      <- soakTimeFilter.find { case (soakTimeBin, _) => soakTimeBin.has(soakTimeIntMinutes) }
      (_, processFilter)  <- tazFilter.find { case (taz, _) => taz.toString == zone.trim.toLowerCase }
      (_, rate)           <- processFilter.find { case (emissionsProcess, _) => emissionsProcess.equals(process) }
    } yield rate
  }
}

object VehicleEmissions {

  object EmissionsRateFilterStore {

    // speed -> (gradePercent -> (weight -> (soakTime -> (taz -> (emissionProcess -> rate)))))
    type EmissionsRateFilter = Map[
      DoubleTypedRange, // speed
      Map[
        DoubleTypedRange, // grade percent
        Map[
          DoubleTypedRange, // weight
          Map[
            DoubleTypedRange, // soak time
            Map[
              String, // taz
              Map[
                EmissionsProcesses.EmissionsProcess, // emissionProcess
                Emissions // rate
              ]
            ]
          ]
        ]
      ]
    ]
  }

  object EmissionsProcesses extends Enumeration {
    type EmissionsProcess = Value
    val RUNEX, IDLEX, STREX, HOTSOAK, DIURN, RUNLOSS, PMTW, PMBW = Value

    def fromString(process: String): EmissionsProcess = process.toLowerCase match {
      // Running Exhaust Emissions (RUNEX) that come out of the vehicle tailpipe while traveling on the road.
      // TODO Embed it in PathTraversalEvent
      // xVMT by speed bin => gram/veh-mile
      case "running" | "runex" => RUNEX

      // Idle Exhaust Emissions (IDLEX) that come out of the vehicle tailpipe while it is operating but not traveling
      // any significant distance. This process captures emissions from heavy-duty vehicles that idle for
      // extended periods of time while loading or unloading goods. Idle exhaust is calculated only
      // for heavy-duty trucks.
      // TODO Embed it in LeavingParkingEvent when 1) it is freight Load/Unload 2) overnight parking
      // xNumber of Idle Hours (xParking Hour) => gram/veh-idle hour
      case "hotelling" | "idle" | "idlex" => IDLEX

      // Start Exhaust Tailpipe Emissions (STREX) that occur when starting a vehicle. These emissions are independent
      // of running exhaust emissions and represent the emissions occurring during the initial time period when
      // a vehicle’s emissions after treatment system is warming up. The magnitude of these emissions is dependent
      // on how long the vehicle has been sitting prior to starting. Please note that STREX is defined differently
      // for heavy-duty diesel trucks than for other vehicles.
      // More details can be found in the EMFAC2014 Technical Support Document.
      // TODO Embed it in LeavingParkingEvent
      // xNumber of starts per Soak time => gram/veh-start
      case "start" | "strex" => STREX

      // Diurnal Evaporative HC Emissions (DIURN) that occur when rising ambient temperatures cause fuel evaporation
      // from vehicles sitting throughout the day. These losses are from leaks in the fuel system, fuel hoses,
      // connectors, as a result of the breakthrough of vapors from the carbon canister.
      // TODO Embed it in LeavingParkingEvent
      // xCold soak hours (xParking Hour) => gram/veh-hour
      case "diurnal" | "diurn" => DIURN

      // Hot Soak Evaporative HC Emissions (HOTSOAK) that begin immediately from heated fuels after a car stops its
      // engine operation and continue until the fuel tank reaches ambient temperature.
      // TODO Embed it in LeavingParkingEvent
      // xNumber of starts => gram/veh-start
      case "hotsoak" => HOTSOAK

      // Running Loss Evaporative HC Emissions (RUNLOSS) that occur as a result of hot fuel vapors escaping
      // from the fuel system or overwhelming the carbon canister while the vehicle is operating.
      // TODO Embed it in PathTraversalEvent and LeavingParkingEvent (loading/unloading/hotelling)
      // xRunning hours (xVHT) => gram/veh-hour
      case "runloss" => RUNLOSS

      // Tire Wear Particulate Matter Emissions (PMTW) that originate from tires as a result of wear.
      // TODO Embed it in PathTraversalEvent
      // xVMT => gram/veh-mile
      case "tirewear" | "pmtw" => PMTW // Embedded in PathTraversalEvent

      // Brake Wear Particulate Matter Emissions (PMBW) that originate from brake usage.
      // TODO Embed it in PathTraversalEvent
      // xVMT by speed bin => gram/veh-mile
      case "brakewear" | "pmbw" => PMBW

      // if process is not recognized then RUNEX emission will be used
      case _ => RUNEX
    }

    import BeamVehicle.VehicleActivityData

    val calculationMap: Map[EmissionsProcess, (Emissions, VehicleActivityData) => Emissions] = Map(
      /**
        * Calculate Running Exhaust Emissions (RUNEX)
        * VMT by speed bin => gram/veh-mile
        * vmt Vehicle Miles Traveled (VMT)
        * ratesBySpeedBin Emission rate by speed bin (grams per vehicle-mile)
        * @return Total emissions in grams
        */
      RUNEX -> { (ratesBySpeedBin: Emissions, data: VehicleActivityData) =>
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
      IDLEX -> { (rates: Emissions, data: VehicleActivityData) =>
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
      STREX -> { (ratesBySoakTime: Emissions, _: VehicleActivityData) =>
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
      DIURN -> { (rates: Emissions, data: VehicleActivityData) =>
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
      HOTSOAK -> { (rates: Emissions, _: VehicleActivityData) =>
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
      RUNLOSS -> { (rates: Emissions, data: VehicleActivityData) =>
        val vehicleHoursTraveledInHours = data.linkTravelTime.map(_ / 3600.0).getOrElse(0.0)
        rates * vehicleHoursTraveledInHours
      },
      /**
        * Calculate Tire Wear Particulate Matter Emissions (PMTW)
        * VMT => gram/veh-mile
        * vmt Vehicle Miles Traveled (VMT)
        * rates Emission rate (grams per vehicle-mile)
        * @return Total emissions in grams
        */
      PMTW -> { (rates: Emissions, data: VehicleActivityData) =>
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
      PMBW -> { (ratesBySpeedBin: Emissions, data: VehicleActivityData) =>
        val vehicleMilesTraveledInMiles = data.linkLength.map(_ / 1609.344).getOrElse(0.0)
        ratesBySpeedBin * vehicleMilesTraveledInMiles
      }
    )
  }

  // Rates in Grams per Mile
  case class Emissions(
    var CH4: Double,
    var CO: Double,
    var CO2: Double,
    var HC: Double,
    var NH3: Double,
    var NOx: Double,
    var PM: Double,
    var PM10: Double,
    var PM2_5: Double,
    var ROG: Double,
    var SOx: Double,
    var TOG: Double
  ) {
    import Emissions._
    def notValid: Boolean = List(CH4, CO, CO2, HC, NH3, NOx, PM, PM10, PM2_5, ROG, SOx, TOG).forall(_ == 0)

    def *(factor: Double): Emissions = {
      Emissions(
        CH4 * factor,
        CO * factor,
        CO2 * factor,
        HC * factor,
        NH3 * factor,
        NOx * factor,
        PM * factor,
        PM10 * factor,
        PM2_5 * factor,
        ROG * factor,
        SOx * factor,
        TOG * factor
      )
    }

    def +(other: Emissions): Emissions = {
      Emissions(
        CH4 + other.CH4,
        CO + other.CO,
        CO2 + other.CO2,
        HC + other.HC,
        NH3 + other.NH3,
        NOx + other.NOx,
        PM + other.PM,
        PM10 + other.PM10,
        PM2_5 + other.PM2_5,
        ROG + other.ROG,
        SOx + other.SOx,
        TOG + other.TOG
      )
    }

    override def toString: String = {
      s"Emissions(" +
      s"${_CH4}=$CH4,${_CO}=$CO,${_CO2}=$CO2,${_HC}=$HC,${_NH3}=$NH3,${_NOx}=$NOx,${_PM}=$PM,${_PM10}=$PM10," +
      s"${_PM2_5}=$PM2_5,${_ROG}=$ROG,${_SOx}=$SOx,${_TOG}=$TOG" +
      s")"
    }
  }

  object Emissions {
    val _CH4: String = "CH4"
    val _CO: String = "CO"
    val _CO2: String = "CO2"
    val _HC: String = "HC"
    val _NH3: String = "NH3"
    val _NOx: String = "NOx"
    val _PM: String = "PM"
    val _PM10: String = "PM10"
    val _PM2_5: String = "PM2_5"
    val _ROG: String = "ROG"
    val _SOx: String = "SOx"
    val _TOG: String = "TOG"

    def init(): Emissions =
      Emissions(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
  }

  object EmissionsProfile {
    type EmissionsProfile = Map[EmissionsProcesses.EmissionsProcess, Emissions]

    def init(): EmissionsProfile = {
      Map {
        EmissionsProcesses.RUNEX   -> Emissions.init()
        EmissionsProcesses.IDLEX   -> Emissions.init()
        EmissionsProcesses.STREX   -> Emissions.init()
        EmissionsProcesses.DIURN   -> Emissions.init()
        EmissionsProcesses.HOTSOAK -> Emissions.init()
        EmissionsProcesses.RUNLOSS -> Emissions.init()
        EmissionsProcesses.PMTW    -> Emissions.init()
        EmissionsProcesses.PMBW    -> Emissions.init()
      }
    }
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
    private val tazHeader = "taz"
    /*
    EmissionsProcess column can either be:
    RUNEX, IDLEX, STREX, DIURN, HOATSOAK, RUNLOSS, PMTW, PMBW
        Running (i.e. Running Exhaust)
        Start (i.e. Start Exhaust)
        Hoteling (i.e. Hoteling Exhaust)
        Dust (i.e. Brake/tire wear and road dust)
        Evaporative (i.e. Evaporative emissions)
        Other (also if the EmissionsProcess column is not present or the value is not recognized then)
    rate_XXX is in grams per mile
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
      import beam.utils.BeamVehicleUtils._

      val currentRateFilter = mutable.Map.empty[DoubleTypedRange, mutable.Map[DoubleTypedRange, mutable.Map[
        DoubleTypedRange,
        mutable.Map[DoubleTypedRange, mutable.Map[String, mutable.Map[EmissionsProcesses.EmissionsProcess, Emissions]]]
      ]]]

      baseFilePaths.foreach(baseFilePath =>
        getVehicleEmissionsRecordsUsing(csvParser, java.nio.file.Paths.get(baseFilePath, file).toString)
          .foreach(csvRecord => {
            // Speed Bin in MPH

            val speedInMilesPerHourBin =
              convertRecordStringToDoubleTypedRange(getString(csvRecord, speedBinHeader, "(0,200]"))
            // Road Grade Bin in PERCENTAGE
            val gradePercentBin =
              convertRecordStringToDoubleTypedRange(getString(csvRecord, gradeBinHeader, "(-100,100]"))
            // Weight in Kg
            val weightKgBin =
              convertRecordStringToDoubleTypedRange(getString(csvRecord, weightBinHeader, "(0,200000]"))
            // Soak Time in minutes
            val soakTimeBin =
              convertRecordStringToDoubleTypedRange(getString(csvRecord, soakTimeBinHeader, "(0,216000]"))
            // Geographic area, None if not defined
            val taz = getString(csvRecord, tazHeader, "")
            // Emission process
            val emissionProcess = EmissionsProcesses.fromString(getString(csvRecord, emissionsProcessHeader, ""))

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
              CH4 = readRateCheckIfNull(rateCH4Header),
              CO = readRateCheckIfNull(rateCOHeader),
              CO2 = readRateCheckIfNull(rateCO2Header),
              HC = readRateCheckIfNull(rateHCHeader),
              NH3 = readRateCheckIfNull(rateNH3Header),
              NOx = readRateCheckIfNull(rateNOxHeader),
              PM = readRateCheckIfNull(ratePMHeader),
              PM10 = readRateCheckIfNull(ratePM10Header),
              PM2_5 = readRateCheckIfNull(ratePM2_5Header),
              ROG = readRateCheckIfNull(rateROGHeader),
              SOx = readRateCheckIfNull(rateSOxHeader),
              TOG = readRateCheckIfNull(rateTOGHeader)
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
                          case Some(tazFilter) =>
                            tazFilter.get(taz) match {
                              case Some(emissionsProcessFilter) =>
                                emissionsProcessFilter.get(emissionProcess) match {
                                  case Some(existingRates) =>
                                    log.error(
                                      "Two emission rates found for the same bin combination: " +
                                      "TAZ = {}; Speed In Miles Per Hour Bin = {}; " +
                                      "Grade Percent Bin = {}; Weight kg Bin = {}; Soak Time Bin = {}. " +
                                      s"Keeping first rate of $existingRates and ignoring new rate of $ratesInGramsPerMile.",
                                      taz,
                                      speedInMilesPerHourBin,
                                      gradePercentBin,
                                      weightKgBin,
                                      soakTimeBin
                                    )
                                  case None =>
                                    emissionsProcessFilter += emissionProcess -> ratesInGramsPerMile
                                }
                              case None =>
                                tazFilter += taz -> mutable.Map(emissionProcess -> ratesInGramsPerMile)
                            }
                          case None =>
                            soakTimeFilter += soakTimeBin -> mutable.Map(
                              taz -> mutable.Map(emissionProcess -> ratesInGramsPerMile)
                            )
                        }
                      case None =>
                        weightKgFilter += weightKgBin -> mutable.Map(
                          soakTimeBin -> mutable.Map(
                            taz -> mutable.Map(emissionProcess -> ratesInGramsPerMile)
                          )
                        )
                    }
                  case None =>
                    gradePercentFilter += gradePercentBin -> mutable.Map(
                      weightKgBin -> mutable.Map(
                        soakTimeBin -> mutable.Map(
                          taz -> mutable.Map(emissionProcess -> ratesInGramsPerMile)
                        )
                      )
                    )
                }
              case None =>
                currentRateFilter += speedInMilesPerHourBin -> mutable.Map(
                  gradePercentBin -> mutable.Map(
                    weightKgBin -> mutable.Map(
                      soakTimeBin -> mutable.Map(
                        taz -> mutable.Map(emissionProcess -> ratesInGramsPerMile)
                      )
                    )
                  )
                )
            }
          })
      )
      currentRateFilter.toMap.map { case (speedInMilesPerHourBin, gradePercentMap) =>
        speedInMilesPerHourBin -> gradePercentMap.toMap.map { case (gradePercentBin, weightMap) =>
          gradePercentBin -> weightMap.toMap.map { case (weightKgBin, soakTimeMap) =>
            weightKgBin -> soakTimeMap.toMap.map { case (soakTimeBin, tazMap) =>
              soakTimeBin -> tazMap.toMap.map { case (taz, emissionsProcessMap) =>
                taz -> emissionsProcessMap.toMap
              }
            }
          }
        }
      }
    }
  }
}
