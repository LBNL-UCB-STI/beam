package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.FuelType.{Diesel, Electricity, Gasoline, NaturalGas}
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
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions.{FuelFilter, RatesFilter}
import beam.utils.BeamVehicleUtils.convertRecordStringToDoubleTypedRange
import beam.utils.geospatial.GeoReader
import beam.utils.{BeamVehicleUtils, NetworkHelper, ParquetReader}
import com.typesafe.scalalogging.LazyLogging
import com.univocity.parsers.common.record.Record
import com.univocity.parsers.csv.{CsvParser, CsvParserSettings}
import org.geotools.data.shapefile.ShapefileDataStore
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.locationtech.jts.geom.{Envelope, Geometry}
import org.locationtech.jts.geom.prep.{PreparedGeometry, PreparedGeometryFactory}
import org.apache.avro.AvroRuntimeException
import org.apache.avro.generic.GenericRecord
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Network
import org.matsim.core.utils.io.IOUtils
import org.matsim.core.utils.geometry.geotools.MGC
import org.opengis.referencing.operation.MathTransform
import org.slf4j.LoggerFactory

import java.io.File
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class VehicleEmissions(
  vehicleTypesBasePaths: IndexedSeq[String],
  vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType],
  countyResolver: VehicleEmissions.CountyResolver,
  pollutantsFilter: String,
  fuelFilter: FuelFilter,
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
    val emissionsProfiles = (for {
      data <- vehicleActivityData
      EmissionsProcessAndRatesStore(process, ratesStore, emissionsRatesFile) <- identifyProcesses(
        data,
        vehicleActivity,
        emissionsRatesFilterStore,
        fuelFilter
      )
      rates <- getRatesUsing(data, process, ratesStore, emissionsRatesFile, beamServices.networkHelper)

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
      if (emissions.notValid) {
        None
      } else {
        Some(process -> emissions)
      }
    }).flatten

    if (emissionsProfiles.isEmpty) {
      None
    } else Some(EmissionsProfile(emissionsProfiles.toMap))
  }

  private def getRatesUsing(
    data: BeamVehicle.VehicleActivityData,
    process: EmissionsProcess,
    ratesStore: EmissionsRateFilter,
    emissionsRatesFile: Option[String],
    networkHelper: NetworkHelper
  ): Option[Emissions] = {
    val speedMph =
      data.averageSpeed.map(BeamVehicleUtils.convertFromMetersPerSecondToMilesPerHour).getOrElse(0.0)
    val soakTimeMin = data.parkingDuration.map(_ / 60.0).getOrElse(0.0)
    val county = countyResolver.resolve(data.linkId).getOrElse("")
    val roadCategory =
      networkHelper
        .getLink(data.linkId)
        .flatMap(link => Option(link.getAttributes.getAttribute("type")).map(_.toString.toLowerCase))
        .getOrElse("unclassified")
    val processStr = process.toString
    val activityValue = if (usesTimeLikeActivityBin(process)) soakTimeMin else speedMph
    val preferWiderActivityRanges =
      if (usesTimeLikeActivityBin(process)) !containsConfiguredProcess(ratesFilter.soakTime, processStr)
      else !containsConfiguredProcess(ratesFilter.speed, processStr)
    val vehicleTypeId = data.vehicleType.id.toString

    val countyMatch = findString(ratesStore, county, !containsConfiguredProcess(ratesFilter.county, processStr))
    if (countyMatch.isEmpty) {
      recordLookupMiss(
        kind = "county",
        details = Seq(
          "vehicleType"        -> vehicleTypeId,
          "emissionsRatesFile" -> emissionsRatesFile.getOrElse("<none>"),
          "process"            -> processStr,
          "county"             -> county
        )
      )
      return None
    }
    val (matchedCounty, countyFilter) = countyMatch.get

    val processMatch = VehicleEmissions.findString(countyFilter, process.toString, preferEmptyKey = false)
    if (processMatch.isEmpty) {
      recordLookupMiss(
        kind = "process",
        details = Seq(
          "vehicleType"        -> vehicleTypeId,
          "emissionsRatesFile" -> emissionsRatesFile.getOrElse("<none>"),
          "county"             -> matchedCounty,
          "process"            -> processStr
        )
      )
      return None
    }
    val (matchedProcess, processIndex) = processMatch.get

    val processLookup = processIndex.find(
      roadCategory = roadCategory,
      preferEmptyRoadCategory = !containsConfiguredProcess(ratesFilter.roadCategory, processStr),
      activityValue = activityValue,
      preferWiderActivityRanges = preferWiderActivityRanges
    )
    if (processLookup.isEmpty) {
      if (processIndex.usesRoadCategory) {
        recordLookupMiss(
          kind = "roadCategory",
          details = Seq(
            "vehicleType"        -> vehicleTypeId,
            "emissionsRatesFile" -> emissionsRatesFile.getOrElse("<none>"),
            "county"             -> matchedCounty,
            "process"            -> matchedProcess,
            "roadCategory"       -> roadCategory
          )
        )
      } else if (processIndex.usesActivityBin) {
        recordLookupMiss(
          kind = "activityBin",
          details = Seq(
            "vehicleType"        -> vehicleTypeId,
            "emissionsRatesFile" -> emissionsRatesFile.getOrElse("<none>"),
            "county"             -> matchedCounty,
            "process"            -> matchedProcess,
            "activityValue"      -> f"$activityValue%.3f"
          )
        )
      }
      return None
    }
    val lookupMatch = processLookup.get
    val matchedRoadCategory = lookupMatch.matchedRoadCategory
    val matchedActivityBin = lookupMatch.matchedActivityBin
    val rates = lookupMatch.rates

    Some(rates)
  }
}

object VehicleEmissions extends LazyLogging {

  private def findString[T](
    map: Map[String, T],
    value: String,
    preferEmptyKey: Boolean = true
  ): Option[(String, T)] = {
    if (preferEmptyKey) {
      map.get("").map("" -> _).orElse(map.get(value).map(value -> _))
    } else {
      map.get(value).map(value -> _).orElse(map.get("").map("" -> _))
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

  trait CountyResolver {
    def resolve(linkId: Int): Option[String]
  }

  object CountyResolver {

    private case class CountyGeometry(
      county: String,
      geometry: PreparedGeometry,
      rawGeometry: Geometry,
      envelope: Envelope
    )

    private object EmptyCountyResolver extends CountyResolver {
      override def resolve(linkId: Int): Option[String] = None
    }

    def build(
      network: Network,
      localCrs: String,
      countyLookup: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions.CountyLookup,
      emissionsEnabled: Boolean
    ): CountyResolver = {
      if (!emissionsEnabled) {
        EmptyCountyResolver
      } else {
        require(
          countyLookup.filePath.trim.nonEmpty,
          "Emissions countyLookup.filePath must be configured when emissions are enabled."
        )
        require(
          countyLookup.countyFieldName.trim.nonEmpty,
          "Emissions countyLookup.countyFieldName must be configured when emissions are enabled."
        )

        val countyFile = new File(countyLookup.filePath)
        require(
          countyFile.exists(),
          s"Emissions county lookup file does not exist: ${countyFile.getPath}"
        )

        validateCountyLookupSchema(countyFile, countyLookup.countyFieldName)
        val countyGeometries = loadCountyGeometries(localCrs, countyFile.getPath, countyLookup.countyFieldName)
        require(
          countyGeometries.nonEmpty,
          s"Emissions county lookup file ${countyFile.getPath} did not contain any usable polygons."
        )

        logger.info(
          s"Building emissions county resolver from ${countyFile.getPath} using field '${countyLookup.countyFieldName}'."
        )

        val links = network.getLinks.values.asScala.toSeq
        val maxLinkId = links.map(_.getId.toString.toInt).max
        val linkIdToCounty = Array.fill[String](maxLinkId + 1)(null)
        var resolvedWithinPolygon = 0
        var resolvedToNearestCounty = 0

        links.foreach { link =>
          val point = MGC.coord2Point(link.getCoord)
          val x = point.getX
          val y = point.getY
          val containingCounty = countyGeometries.collectFirst {
            case CountyGeometry(name, geometry, _, envelope) if envelope.contains(x, y) && geometry.contains(point) =>
              name
          }
          val resolvedCounty = containingCounty.orElse {
            if (countyGeometries.nonEmpty) {
              Some(countyGeometries.minBy(countyGeometry => countyGeometry.rawGeometry.distance(point)).county)
            } else {
              None
            }
          }
          resolvedCounty.foreach { value =>
            linkIdToCounty(link.getId.toString.toInt) = value
            if (containingCounty.contains(value)) {
              resolvedWithinPolygon += 1
            } else {
              resolvedToNearestCounty += 1
            }
          }
        }

        logger.info(
          s"Built emissions county resolver for ${links.size} links. " +
          s"ResolvedWithinPolygon=$resolvedWithinPolygon, " +
          s"resolvedToNearestCounty=$resolvedToNearestCounty, " +
          s"unresolved=${links.size - resolvedWithinPolygon - resolvedToNearestCounty}."
        )

        new CountyResolver {
          override def resolve(linkId: Int): Option[String] =
            if (linkId >= 0 && linkId < linkIdToCounty.length) Option(linkIdToCounty(linkId)) else None
        }
      }
    }

    private def validateCountyLookupSchema(countyFile: File, countyFieldName: String): Unit = {
      val features = GeoReader.readFeatures(countyFile.getPath).asScala.toSeq
      require(features.nonEmpty, s"Emissions county lookup file ${countyFile.getPath} does not contain any features.")

      val firstFeature = features.head
      val geometryDescriptor = firstFeature.getFeatureType.getGeometryDescriptor
      require(
        geometryDescriptor != null,
        s"Emissions county lookup file ${countyFile.getPath} does not contain a geometry column."
      )

      val attributeNames = firstFeature.getFeatureType.getAttributeDescriptors.asScala.map(_.getLocalName).toSeq
      require(
        attributeNames.exists(_.equalsIgnoreCase(countyFieldName)),
        s"Emissions county lookup file ${countyFile.getPath} does not contain required county field '$countyFieldName'. Available fields: ${attributeNames.sorted
          .mkString(", ")}"
      )
    }

    private def loadCountyGeometries(
      localCrs: String,
      path: String,
      countyFieldName: String
    ): IndexedSeq[CountyGeometry] = {
      val countyFieldNameLower = countyFieldName.toLowerCase
      val mathTransform = createCountyLookupTransform(path, localCrs)
      GeoReader
        .readFeatures(path)
        .asScala
        .toIndexedSeq
        .map { feature =>
          val county = feature.getProperties.asScala
            .find(_.getName.toString.equalsIgnoreCase(countyFieldNameLower))
            .map(_.getValue.toString.trim.toLowerCase)
            .getOrElse(
              throw new IllegalArgumentException(
                s"Feature ${feature.getID} in emissions county lookup file $path is missing required county field '$countyFieldName'."
              )
            )
          val geometry = Option(feature.getDefaultGeometry)
            .map(_.asInstanceOf[Geometry])
            .getOrElse(
              throw new IllegalArgumentException(
                s"Feature ${feature.getID} in emissions county lookup file $path is missing geometry."
              )
            )
          county -> JTS.transform(geometry, mathTransform)
        }
        .toIndexedSeq
        .map { case (county, geometry) =>
          CountyGeometry(
            county,
            new PreparedGeometryFactory().create(geometry),
            geometry,
            geometry.getEnvelopeInternal
          )
        }
    }

    private def createCountyLookupTransform(path: String, localCrs: String): MathTransform = {
      val targetCrs = CRS.decode(localCrs, true)
      val sourceCrs =
        if (path.toLowerCase.endsWith(".geojson")) {
          CRS.decode("EPSG:4326", true)
        } else if (path.toLowerCase.endsWith(".shp")) {
          val dataStore = new ShapefileDataStore(new File(path).toURI.toURL)
          try {
            dataStore.getSchema.getCoordinateReferenceSystem
          } finally {
            dataStore.dispose()
          }
        } else {
          throw new IllegalArgumentException(
            s"Unsupported emissions county lookup file format for $path. Supported: .shp, .geojson"
          )
        }
      CRS.findMathTransform(sourceCrs, targetCrs, true)
    }
  }

  case class EmissionsProcessAndRatesStore(
    process: EmissionsProcess,
    ratesStore: EmissionsRateFilter,
    emissionsRatesFile: Option[String]
  )

  private val canonicalProcessesByUppercaseName: Map[String, String] =
    EmissionsProfile.values.map(process => process.toString.toUpperCase -> process.toString).toMap

  private def canonicalConfiguredProcess(process: String): Option[String] = {
    val normalized = process.trim.toUpperCase
    canonicalProcessesByUppercaseName.get(normalized).orElse {
      if (normalized.nonEmpty && shouldWarn(s"invalid-configured-process:$normalized")) {
        logger.warn(
          s"Unrecognized configured emissions process '$process'. Supported values are: " +
          canonicalProcessesByUppercaseName.values.toSeq.sorted.mkString(", ")
        )
      }
      None
    }
  }

  private def parseConfiguredProcesses(configValue: String): Set[String] =
    Option(configValue).toSeq
      .flatMap(_.split(","))
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(canonicalConfiguredProcess)
      .toSet

  private def containsConfiguredProcess(configValue: String, process: String): Boolean =
    parseConfiguredProcesses(configValue).contains(process.trim.toUpperCase)

  private val warningCounts: TrieMap[String, AtomicInteger] = TrieMap.empty
  private val lookupMissCounts: TrieMap[String, AtomicInteger] = TrieMap.empty
  private val lookupMissTotals: TrieMap[String, AtomicInteger] = TrieMap.empty
  private val lookupMissSummaryFrequency = 5000

  private def shouldWarn(key: String): Boolean =
    warningCounts.getOrElseUpdate(key, new AtomicInteger(0)).incrementAndGet() == 1

  private def incrementCounter(counterMap: TrieMap[String, AtomicInteger], key: String): Int = {
    val counter = counterMap.getOrElseUpdate(key, new AtomicInteger(0))
    counter.incrementAndGet()
  }

  private def recordLookupMiss(kind: String, details: Seq[(String, String)]): Unit = {
    val detailString = details.map { case (key, value) => s"$key=$value" }.mkString(", ")
    val aggregateKey = s"$kind|${details.map { case (key, value) => s"$key=$value" }.mkString("|")}"
    val entryCount = incrementCounter(lookupMissCounts, aggregateKey)
    val totalCount = incrementCounter(lookupMissTotals, kind)

    if (entryCount <= 3) {
      logger.debug(s"Emissions lookup miss [$kind]: $detailString (occurrence=$entryCount)")
    }
    if (totalCount == 1 || totalCount % lookupMissSummaryFrequency == 0) {
      logLookupMissSummary(kind, totalCount)
    }
  }

  private def logLookupMissSummary(kind: String, totalCount: Int): Unit = {
    val kindPrefix = s"$kind|"
    val topMisses = lookupMissCounts.iterator
      .collect {
        case (key, count) if key.startsWith(kindPrefix) =>
          key.stripPrefix(kindPrefix) -> count.get()
      }
      .toSeq
      .sortBy { case (_, count) => -count }
      .take(10)
      .map { case (key, count) => s"$count x [$key]" }
      .mkString("; ")

    logger.warn(
      s"Emissions lookup miss summary [$kind]: total=$totalCount, unique=${lookupMissCounts.keys
        .count(_.startsWith(kindPrefix))}, top=[$topMisses]"
    )
  }

  private def usesTimeLikeActivityBin(process: EmissionsProcess): Boolean =
    Set(EmissionsProfile.STREX, EmissionsProfile.DIURN, EmissionsProfile.HOTSOAK, EmissionsProfile.RUNLOSS).contains(
      process
    )

  private def multiplyIfPositive(rates: Emissions, factor: Double): Emissions =
    if (factor <= 0.0) Emissions() else rates * factor

  object EmissionsRateFilterStore {

    sealed trait ActivityLookupMode
    case object NoActivityLookup extends ActivityLookupMode
    case object ActivityBinLookup extends ActivityLookupMode

    case class ProcessLookupMatch(
      matchedRoadCategory: Option[String],
      matchedActivityBin: Option[DoubleTypedRange],
      rates: Emissions
    )

    case class ProcessRateIndex(
      roadCategoryToActivityRates: Map[String, Map[DoubleTypedRange, Emissions]],
      usesRoadCategory: Boolean,
      activityLookupMode: ActivityLookupMode
    ) {

      def usesActivityBin: Boolean = activityLookupMode == ActivityBinLookup

      def find(
        roadCategory: String,
        preferEmptyRoadCategory: Boolean,
        activityValue: Double,
        preferWiderActivityRanges: Boolean
      ): Option[ProcessLookupMatch] = {
        val roadMatch =
          if (usesRoadCategory) {
            VehicleEmissions.findString(roadCategoryToActivityRates, roadCategory, preferEmptyRoadCategory)
          } else {
            roadCategoryToActivityRates.get("").map("" -> _).orElse(roadCategoryToActivityRates.headOption)
          }

        roadMatch.flatMap { case (matchedRoadCategory, ratesByActivity) =>
          val activityMatch =
            activityLookupMode match {
              case ActivityBinLookup =>
                VehicleEmissions.findInterval(ratesByActivity, activityValue, preferWiderActivityRanges)
              case NoActivityLookup =>
                ratesByActivity.toSeq.sortBy { case (range, _) => (range.lowerBound, range.upperBound) }.headOption
            }
          activityMatch.map { case (matchedActivityBin, rates) =>
            ProcessLookupMatch(
              matchedRoadCategory = if (usesRoadCategory) Some(matchedRoadCategory) else None,
              matchedActivityBin = if (usesActivityBin) Some(matchedActivityBin) else None,
              rates = rates
            )
          }
        }
      }
    }

    object ProcessRateIndex {

      def fromRaw(
        process: String,
        roadCategoryToActivityRates: Map[String, Map[DoubleTypedRange, Emissions]]
      ): ProcessRateIndex = {
        val normalizedProcess = process.trim.toUpperCase
        val usesRoadCategory = roadCategoryToActivityRates.keys.exists(_.nonEmpty)
        val activityLookupMode =
          if (
            Set(
              EmissionsProfile.RUNEX.toString,
              EmissionsProfile.PTOEX.toString,
              EmissionsProfile.PMBW.toString,
              EmissionsProfile.PMTW.toString,
              EmissionsProfile.PRDUST.toString,
              EmissionsProfile.STREX.toString,
              EmissionsProfile.DIURN.toString,
              EmissionsProfile.HOTSOAK.toString,
              EmissionsProfile.RUNLOSS.toString
            ).contains(normalizedProcess)
          ) ActivityBinLookup
          else NoActivityLookup

        ProcessRateIndex(
          roadCategoryToActivityRates = roadCategoryToActivityRates,
          usesRoadCategory = usesRoadCategory,
          activityLookupMode = activityLookupMode
        )
      }
    }

    // county -> (emissionProcess -> process-specific lookup index)
    type EmissionsRateFilter = Map[
      String, // county
      Map[
        String, // emissionProcess
        ProcessRateIndex
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
            logger.debug(s"Keeping only the following pollutants: $keeping")
            logger.debug(s"Filtering out: $filtering")
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

    sealed trait EmissionsFuelGroup {
      def configuredProcesses(fuelFilter: FuelFilter): Set[String]
    }

    object EmissionsFuelGroup {

      case object GasolinePowered extends EmissionsFuelGroup {

        override def configuredProcesses(fuelFilter: FuelFilter): Set[String] =
          parseConfiguredProcesses(fuelFilter.gasoline)
      }

      case object DieselPowered extends EmissionsFuelGroup {

        override def configuredProcesses(fuelFilter: FuelFilter): Set[String] =
          parseConfiguredProcesses(fuelFilter.diesel)
      }

      case object NaturalGasPowered extends EmissionsFuelGroup {

        override def configuredProcesses(fuelFilter: FuelFilter): Set[String] =
          parseConfiguredProcesses(fuelFilter.naturalgas)
      }

      case object PhevPowered extends EmissionsFuelGroup {

        override def configuredProcesses(fuelFilter: FuelFilter): Set[String] =
          parseConfiguredProcesses(fuelFilter.phev)
      }

      case object ElectricPowered extends EmissionsFuelGroup {

        override def configuredProcesses(fuelFilter: FuelFilter): Set[String] =
          parseConfiguredProcesses(fuelFilter.electric)
      }
    }

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

    private def fuelGroupFor(vehicleType: BeamVehicleType): Option[EmissionsFuelGroup] = {
      import EmissionsFuelGroup._

      (vehicleType.primaryFuelType, vehicleType.secondaryFuelType) match {
        case (Electricity, Some(_)) => Some(PhevPowered)
        case (Electricity, None)    => Some(ElectricPowered)
        case (Gasoline, _)          => Some(GasolinePowered)
        case (Diesel, _)            => Some(DieselPowered)
        case (NaturalGas, _)        => Some(NaturalGasPowered)
        case _                      => None
      }
    }

    def identifyProcesses(
      data: BeamVehicle.VehicleActivityData,
      event: Class[_ <: org.matsim.api.core.v01.events.Event],
      emissionsRatesFilterStore: EmissionsRateFilterStore,
      fuelFilter: FuelFilter
    ): IndexedSeq[EmissionsProcessAndRatesStore] = {
      emissionsRatesFilterStore
        .getEmissionsRateFilterFor(data.vehicleType)
        .map(future => Await.result(future, 1.minute)) match {
        case Some(rateFilter) =>
          val allowedProcessesByFuel = fuelGroupFor(data.vehicleType)
            .map(_.configuredProcesses(fuelFilter))
            .getOrElse(Set.empty[String])
          val selectedProcesses = EmissionsProfile.values
            .flatMap {
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
                Some(EmissionsProcessAndRatesStore(process, rateFilter, data.vehicleType.emissionsRatesFile))

              case process @ (RUNEX | PMBW | PMTW | RUNLOSS | PRDUST) if event == classOf[PathTraversalEvent] =>
                Some(EmissionsProcessAndRatesStore(process, rateFilter, data.vehicleType.emissionsRatesFile))

              case process @ PTOEX
                  if event == classOf[PathTraversalEvent] && data.vehicleType.vehicleUse == VehicleUse.Freight =>
                Some(EmissionsProcessAndRatesStore(process, rateFilter, data.vehicleType.emissionsRatesFile))

              case process @ (STREX | DIURN | HOTSOAK | RUNLOSS) if event == classOf[LeavingParkingEvent] =>
                Some(EmissionsProcessAndRatesStore(process, rateFilter, data.vehicleType.emissionsRatesFile))

              case _ => None
            }
            .filter(selected => allowedProcessesByFuel.contains(selected.process.toString))
            .toIndexedSeq

          selectedProcesses
        case _ =>
          IndexedSeq.empty
      }
    }

    def fromString(process: String): Option[EmissionsProcess] = {
      VehicleEmissions.canonicalConfiguredProcess(process).flatMap { canonicalProcess =>
        EmissionsProfile.values.find(_.toString == canonicalProcess).orElse {
          logger.warn(s"Unrecognized emission process: $process")
          None
        }
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

          multiplyIfPositive(ratesBySpeedBin, vehicleMilesTraveledInMiles)
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

          multiplyIfPositive(rates, idlingHours)
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

          multiplyIfPositive(ratesBySoakTime, numberOfVehicleStartTimes)
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

          multiplyIfPositive(rates, vehicleParkingInHours)
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

          multiplyIfPositive(rates, numberOfVehicleStartTimes)
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

          multiplyIfPositive(rates, vehicleHoursTraveledInHours)
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

          multiplyIfPositive(rates, vehicleMilesTraveledInMiles)
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

          multiplyIfPositive(ratesBySpeedBin, vehicleMilesTraveledInMiles)
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

          multiplyIfPositive(rates, vehicleMilesTraveledInMiles * prdustWeightMultiplier)
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

          multiplyIfPositive(ratesBySpeedBin, vehicleMilesTraveledInMiles)
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
        case (_, None)                                    =>
        case (_, Some(filePath)) if filePath.trim.isEmpty =>
      }
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

    private def normalizeLookupKey(value: String): String = value.trim.toLowerCase

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
      val validRowsByProcess = mutable.Map.empty[String, Int].withDefaultValue(0)
      val invalidRowsByProcess = mutable.Map.empty[String, Int].withDefaultValue(0)

      var rowCount = 0
      resolveFilePaths(baseFilePaths, file).foreach { resolvedPath =>
        getVehicleEmissionsRows(csvParser, resolvedPath).foreach { row =>
          rowCount += 1

          val emissionProcess =
            EmissionsProfile
              .fromString(row.getStringAny(emissionsProcessHeaders).getOrElse(""))
              .map(_.toString)
              .getOrElse("")
          val activityBin = getActivityBin(row, emissionProcess)
          val county = normalizeLookupKey(row.getStringAny(countyHeaders).getOrElse(""))
          val roadCategory = normalizeLookupKey(row.getStringAny(roadCategoryHeaders).getOrElse(""))

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
            invalidRowsByProcess.update(emissionProcess, invalidRowsByProcess(emissionProcess) + 1)
            logger.error(
              s"Record ${row.debugString} does not contain a valid rate. Erroring early to bring attention and get it fixed."
            )
          } else {
            validRowsByProcess.update(emissionProcess, validRowsByProcess(emissionProcess) + 1)
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

      logger.debug(
        s"Emission rate load summary for file=$file, totalRows=$rowCount, validRowsByProcess=${validRowsByProcess.toSeq
          .sortBy(_._1)
          .mkString("[", ", ", "]")}, " +
        s"invalidRowsByProcess=${invalidRowsByProcess.toSeq.sortBy(_._1).mkString("[", ", ", "]")}"
      )

      currentRateFilter.toMap.map { case (county, processMap) =>
        county -> processMap.toMap.map { case (emissionProcess, roadCategoryMap) =>
          emissionProcess -> EmissionsRateFilterStore.ProcessRateIndex.fromRaw(
            emissionProcess,
            roadCategoryMap.toMap.map { case (roadCategory, activityBinMap) =>
              roadCategory -> activityBinMap.toMap
            }
          )
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
