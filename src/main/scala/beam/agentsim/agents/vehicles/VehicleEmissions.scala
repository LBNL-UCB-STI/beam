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

import java.io.File
import java.nio.file.Paths
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

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
  private val parsedFuelFilter = ParsedFuelFilter.fromConfig(fuelFilter)
  private val parsedRatesFilter = ParsedRatesFilter.fromConfig(ratesFilter)

  private lazy val emissionsRatesFilterStore = new EmissionsRateFilterStore(
    vehicleTypesBasePaths,
    emissionsRateFilePathsByVehicleType = vehicleTypes.values.map(x => (x, x.emissionsRatesFile)).toIndexedSeq
  )

  private lazy val vehicleOperationTimeTrieMap: TrieMap[Id[BeamVehicle], Double] =
    TrieMap.empty[Id[BeamVehicle], Double]
  private val roadCategoryCache = new AtomicReference[Array[String]]()

  Emissions.setFilter(pollutantsFilter.split(","))

  def getEmissionsProfileInGram(
    vehicleActivityData: IndexedSeq[BeamVehicle.VehicleActivityData],
    vehicleActivity: Class[_ <: org.matsim.api.core.v01.events.Event],
    beamServices: BeamServices
  ): Option[EmissionsProfile] = {
    val emissionsProfiles = mutable.Map.empty[EmissionsProcess, Emissions]
    val emissionsConfig = beamServices.beamConfig.beam.agentsim.agents.vehicles.emissions

    vehicleActivityData.foreach { data =>
      identifyProcesses(
        data,
        vehicleActivity,
        emissionsRatesFilterStore,
        parsedFuelFilter
      ).foreach { case EmissionsProcessAndRatesStore(process, ratesStore, emissionsRatesFile) =>
        getRatesUsing(data, process, ratesStore, emissionsRatesFile, beamServices.networkHelper).foreach { rates =>
          val emissions = calculationMap(process)(
            rates,
            data,
            vehicleOperationTimeTrieMap,
            emissionsConfig
          )
          if (!emissions.notValid) {
            if (emissionsConfig.skims) {
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
            emissionsProfiles.update(process, emissions)
          }
        }
      }
    }

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
    val roadCategory = resolveRoadCategory(data.linkId, networkHelper)
    val processStr = process.toString
    val activityValue = if (usesTimeLikeActivityBin(process)) soakTimeMin else speedMph
    val preferWiderActivityRanges =
      if (usesTimeLikeActivityBin(process)) !parsedRatesFilter.soakTime.contains(processStr)
      else !parsedRatesFilter.speed.contains(processStr)
    val vehicleTypeId = data.vehicleType.id.toString

    val countyMatch = findString(ratesStore.countyToProcessRates, county, !parsedRatesFilter.county.contains(processStr))
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
      preferEmptyRoadCategory = !parsedRatesFilter.roadCategory.contains(processStr),
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

  private def resolveRoadCategory(linkId: Int, networkHelper: NetworkHelper): String = {
    val cached = roadCategoryCache.get()
    if (cached != null && linkId >= 0 && linkId < cached.length) {
      val roadCategory = cached(linkId)
      if (roadCategory != null) return roadCategory
    }

    val built = buildRoadCategoryCache(networkHelper)
    if (linkId >= 0 && linkId < built.length) Option(built(linkId)).getOrElse("unclassified") else "unclassified"
  }

  private def buildRoadCategoryCache(networkHelper: NetworkHelper): Array[String] = {
    val cached = roadCategoryCache.get()
    if (cached != null) cached
    else {
      val built = Array.fill[String](networkHelper.maxLinkId + 1)("unclassified")
      networkHelper.allLinks.foreach { link =>
        if (link != null) {
          val roadCategory =
            Option(link.getAttributes.getAttribute("type")).map(_.toString.trim.toLowerCase).filter(_.nonEmpty)
          built(link.getId.toString.toInt) = roadCategory.getOrElse("unclassified")
        }
      }
      if (roadCategoryCache.compareAndSet(null, built)) built else roadCategoryCache.get()
    }
  }
}

object VehicleEmissions extends LazyLogging {

  case class ParsedFuelFilter(
    gasoline: Set[String],
    diesel: Set[String],
    naturalgas: Set[String],
    phev: Set[String],
    electric: Set[String]
  ) {
    def configuredProcesses(group: EmissionsProfile.EmissionsFuelGroup): Set[String] = group match {
      case EmissionsProfile.EmissionsFuelGroup.GasolinePowered   => gasoline
      case EmissionsProfile.EmissionsFuelGroup.DieselPowered     => diesel
      case EmissionsProfile.EmissionsFuelGroup.NaturalGasPowered => naturalgas
      case EmissionsProfile.EmissionsFuelGroup.PhevPowered       => phev
      case EmissionsProfile.EmissionsFuelGroup.ElectricPowered   => electric
    }
  }

  private object ParsedFuelFilter {
    def fromConfig(fuelFilter: FuelFilter): ParsedFuelFilter =
      ParsedFuelFilter(
        gasoline = parseConfiguredProcesses(fuelFilter.gasoline),
        diesel = parseConfiguredProcesses(fuelFilter.diesel),
        naturalgas = parseConfiguredProcesses(fuelFilter.naturalgas),
        phev = parseConfiguredProcesses(fuelFilter.phev),
        electric = parseConfiguredProcesses(fuelFilter.electric)
      )
  }

  case class ParsedRatesFilter(
    speed: Set[String],
    soakTime: Set[String],
    county: Set[String],
    roadCategory: Set[String]
  )

  private object ParsedRatesFilter {
    def fromConfig(ratesFilter: RatesFilter): ParsedRatesFilter =
      ParsedRatesFilter(
        speed = parseConfiguredProcesses(ratesFilter.speed),
        soakTime = parseConfiguredProcesses(ratesFilter.soakTime),
        county = parseConfiguredProcesses(ratesFilter.county),
        roadCategory = parseConfiguredProcesses(ratesFilter.roadCategory)
      )
  }

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

  case class ActivityRangeEntry[T](
    range: DoubleTypedRange,
    value: T,
    width: Double,
    center: Double
  )

  private def toSortedActivityRangeEntries[T](map: Map[DoubleTypedRange, T]): IndexedSeq[ActivityRangeEntry[T]] =
    map.iterator
      .map { case (range, value) =>
        ActivityRangeEntry(
          range = range,
          value = value,
          width = range.upperBound - range.lowerBound,
          center = (range.lowerBound + range.upperBound) / 2.0
        )
      }
      .toIndexedSeq
      .sortBy(entry => (entry.range.lowerBound, entry.range.upperBound))

  private def findInterval[T](
    entries: IndexedSeq[ActivityRangeEntry[T]],
    value: Double,
    preferWiderRanges: Boolean = true
  ): Option[(DoubleTypedRange, T)] = {
    if (entries.isEmpty) None
    else {
      var matched: ActivityRangeEntry[T] = null
      var index = 0
      while (index < entries.length) {
        val candidate = entries(index)
        if (candidate.range.has(value)) {
          if (
            matched == null ||
            (preferWiderRanges && candidate.width > matched.width) ||
            (!preferWiderRanges && candidate.width < matched.width)
          ) {
            matched = candidate
          }
        }
        index += 1
      }

      if (matched != null) Some(matched.range -> matched.value)
      else {
        var nearest = entries.head
        var smallestDistance = math.abs(nearest.center - value)
        index = 1
        while (index < entries.length) {
          val candidate = entries(index)
          val distance = math.abs(candidate.center - value)
          if (distance < smallestDistance) {
            nearest = candidate
            smallestDistance = distance
          }
          index += 1
        }
        Some(nearest.range -> nearest.value)
      }
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

  private def getOrInitializeOperationTime(
    operationTimeMap: TrieMap[Id[BeamVehicle], Double],
    vehicleId: Id[BeamVehicle],
    activityStartTime: Double
  ): Double =
    operationTimeMap.getOrElseUpdate(vehicleId, activityStartTime)

  private def updateOperationTime(
    operationTimeMap: TrieMap[Id[BeamVehicle], Double],
    vehicleId: Id[BeamVehicle],
    nextOperationTime: Double
  ): Unit =
    operationTimeMap.update(vehicleId, nextOperationTime)

  private def clearOperationTime(
    operationTimeMap: TrieMap[Id[BeamVehicle], Double],
    vehicleId: Id[BeamVehicle]
  ): Unit =
    operationTimeMap.remove(vehicleId)

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
      roadCategoryToActivityRates: Map[String, IndexedSeq[ActivityRangeEntry[Emissions]]],
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
                ratesByActivity.headOption.map(entry => entry.range -> entry.value)
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
          roadCategoryToActivityRates = roadCategoryToActivityRates.iterator
            .map { case (roadCategory, activityRates) =>
              roadCategory -> toSortedActivityRangeEntries(activityRates)
            }
            .toMap,
          usesRoadCategory = usesRoadCategory,
          activityLookupMode = activityLookupMode
        )
      }
    }

    case class EmissionsRateFilter(
      countyToProcessRates: Map[
        String, // county
        Map[
          String, // emissionProcess
          ProcessRateIndex
        ]
      ],
      supportedProcesses: Set[String]
    ) {
      def supportsProcess(process: String): Boolean = supportedProcesses.contains(process.trim.toUpperCase)
    }
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

    sealed trait EmissionsFuelGroup

    object EmissionsFuelGroup {

      case object GasolinePowered extends EmissionsFuelGroup

      case object DieselPowered extends EmissionsFuelGroup

      case object NaturalGasPowered extends EmissionsFuelGroup

      case object PhevPowered extends EmissionsFuelGroup

      case object ElectricPowered extends EmissionsFuelGroup
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
      parsedFuelFilter: ParsedFuelFilter
    ): IndexedSeq[EmissionsProcessAndRatesStore] = {
      emissionsRatesFilterStore.getEmissionsRateFilterFor(data.vehicleType) match {
        case Some(rateFilter) =>
          val allowedProcessesByFuel = fuelGroupFor(data.vehicleType)
            .map(parsedFuelFilter.configuredProcesses)
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
                  if event == classOf[PathTraversalEvent] &&
                    data.vehicleType.vehicleUse == VehicleUse.Freight &&
                    rateFilter.supportsProcess(process.toString) =>
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
          _: TrieMap[Id[BeamVehicle], Double],
          _: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions
        ) =>
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
                  data.linkStartTime - getOrInitializeOperationTime(
                    operationTimeMap,
                    data.vehicleId,
                    data.activityStartTime
                  )
                updateOperationTime(operationTimeMap, data.vehicleId, data.linkStartTime + vehicleParkingInSec)
                val hotellingInHours = (vehicleParkingInSec + operationDurationInSec) / 3600.0
                hotellingInHours

              case _ =>
                val vehicleDurationInSec = data.linkTravelTime.getOrElse(0.0).max(data.parkingDuration.getOrElse(0.0))
                val operationDurationInSec =
                  data.linkStartTime - getOrInitializeOperationTime(
                    operationTimeMap,
                    data.vehicleId,
                    data.activityStartTime
                  )
                updateOperationTime(operationTimeMap, data.vehicleId, data.linkStartTime + vehicleDurationInSec)
                val workingIdleFactor =
                  workdayIdleFactor.get(data.vehicleType.vehicleCategory).map(_(emissionsConfig)).getOrElse(0.0)
                val portionOfIdlingHours =
                  ((operationDurationInSec + vehicleDurationInSec) / 3600.0) * workingIdleFactor
                portionOfIdlingHours
            }

          val emissions = multiplyIfPositive(rates, idlingHours)
          if (emissions.notValid) {
            clearOperationTime(operationTimeMap, data.vehicleId)
          }
          emissions
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
          _: TrieMap[Id[BeamVehicle], Double],
          _: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions
        ) =>
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
          _: TrieMap[Id[BeamVehicle], Double],
          _: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions
        ) =>
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
          _: TrieMap[Id[BeamVehicle], Double],
          _: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions
        ) =>
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
          _: TrieMap[Id[BeamVehicle], Double],
          _: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions
        ) =>
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
          _: TrieMap[Id[BeamVehicle], Double],
          _: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions
        ) =>
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
          _: TrieMap[Id[BeamVehicle], Double],
          _: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions
        ) =>
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
          _: TrieMap[Id[BeamVehicle], Double],
          _: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions
        ) =>
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
          _: TrieMap[Id[BeamVehicle], Double],
          _: BeamConfig.Beam.Agentsim.Agents.Vehicles.Emissions
        ) =>
          val vehicleMilesTraveledInMiles = data.linkLength.map(_ / 1609.344).getOrElse(0.0)

          multiplyIfPositive(ratesBySpeedBin, vehicleMilesTraveledInMiles)
      }
    )
  }

  private class EmissionsRateFilterStore(
    baseFilePaths: IndexedSeq[String],
    emissionsRateFilePathsByVehicleType: IndexedSeq[(BeamVehicleType, Option[String])]
  ) {
    private val emissionRateFiltersByVehicleType: Map[BeamVehicleType, EmissionsRateFilterStore.EmissionsRateFilter] =
      beginLoadingEmissionRateFiltersFor(emissionsRateFilePathsByVehicleType)

    def getEmissionsRateFilterFor(
      vehicleType: BeamVehicleType
    ): Option[EmissionsRateFilterStore.EmissionsRateFilter] = emissionRateFiltersByVehicleType.get(vehicleType)

    private def beginLoadingEmissionRateFiltersFor(
      files: IndexedSeq[(BeamVehicleType, Option[String])]
    ): Map[BeamVehicleType, EmissionsRateFilterStore.EmissionsRateFilter] = {
      files.collect {
        case (vehicleType, Some(filePath)) if filePath.trim.nonEmpty =>
          // Keep parser local to the load; parser instances are not shared.
          val settings = new CsvParserSettings()
          settings.setHeaderExtractionEnabled(true)
          settings.detectFormatAutomatically()
          val csvParser = new CsvParser(settings)
          vehicleType -> EmissionsRateTableLoader.loadFromFile(baseFilePaths, filePath, csvParser)
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

      val countyToProcessRates = currentRateFilter.toMap.map { case (county, processMap) =>
        county -> processMap.toMap.map { case (emissionProcess, roadCategoryMap) =>
          emissionProcess.trim.toUpperCase -> EmissionsRateFilterStore.ProcessRateIndex.fromRaw(
            emissionProcess,
            roadCategoryMap.toMap.map { case (roadCategory, activityBinMap) =>
              roadCategory -> activityBinMap.toMap
            }
          )
        }
      }
      val supportedProcesses =
        validRowsByProcess.collect { case (process, count) if count > 0 => process.trim.toUpperCase }.toSet
      EmissionsRateFilterStore.EmissionsRateFilter(
        countyToProcessRates = countyToProcessRates,
        supportedProcesses = supportedProcesses
      )
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
