package beam.utils.beamToVia

case class Circle(x: Double, y: Double, r: Double) {
  val rSquare: Double = r * r
}

case class VehicleSample(vehicleType: String, percentage: Double)

case class PopulationSample(percentage: Double, personIsInteresting: String => Boolean)

case class RunConfig(
  beamEventsPath: String,
  networkPath: String,
  viaEventsPath: String,
  viaIdGoupsFilePath: String,
  viaIdGoupsDirectoryPath: String,
  viaRunScriptPath: String,
  viaFollowPersonScriptPath: String,
  excludedVehicleIds: Seq[String],
  vehicleSampling: Seq[VehicleSample],
  vehicleSamplingOtherTypes: Double,
  populationSampling: Seq[PopulationSample],
  circleFilter: Seq[Circle],
  buildTrackPersonScript: Boolean,
  vehicleIdPrefix: String
)

object RunConfig {

  def apply(
    beamEventsPath: String,
    networkPath: String,
    viaEventsPath: String,
    viaIdGoupsFilePath: String,
    viaIdGoupsDirectoryPath: String,
    viaRunScriptPath: String,
    viaFollowPersonScriptPath: String,
    excludedVehicleIds: Seq[String],
    vehicleSampling: Seq[VehicleSample],
    vehicleSamplingOtherTypes: Double,
    populationSampling: Seq[PopulationSample],
    circleFilter: Seq[Circle],
    buildTrackPersonScript: Boolean,
    vehicleIdPrefix: String
  ): RunConfig =
    new RunConfig(
      beamEventsPath,
      networkPath,
      viaEventsPath,
      viaIdGoupsFilePath,
      viaIdGoupsDirectoryPath,
      viaRunScriptPath,
      viaFollowPersonScriptPath,
      excludedVehicleIds,
      vehicleSampling,
      vehicleSamplingOtherTypes,
      populationSampling,
      circleFilter,
      buildTrackPersonScript,
      vehicleIdPrefix
    )

  def defaultValues(
    sourcePath: String,
    networkPath: String = "",
    viaEventsPath: String = "",
    viaIdGoupsFilePath: String = "",
    viaIdGoupsDirectoryPath: String = "",
    viaRunScriptPath: String = "",
    viaFollowPersonScriptPath: String = "",
    excludedVehicleIds: Seq[String] = Seq.empty[String],
    vehicleSampling: Seq[VehicleSample] = Seq.empty[VehicleSample],
    vehicleSamplingOtherTypes: Double = 1.0,
    populationSampling: Seq[PopulationSample] = Seq.empty[PopulationSample],
    circleFilter: Seq[Circle] = Seq.empty[Circle],
    buildTrackPersonScript: Boolean = false,
    vehicleIdPrefix: String = ""
  ): RunConfig = RunConfig(
    sourcePath,
    networkPath,
    if (viaEventsPath.isEmpty) sourcePath + ".via.events.xml" else viaEventsPath,
    if (viaIdGoupsFilePath.isEmpty) sourcePath + ".via.ids.txt" else viaIdGoupsFilePath,
    if (viaIdGoupsDirectoryPath.isEmpty) sourcePath + ".via.ids" else viaIdGoupsDirectoryPath,
    viaRunScriptPath,
    if (viaFollowPersonScriptPath.isEmpty) sourcePath + ".via.js" else viaFollowPersonScriptPath,
    excludedVehicleIds,
    vehicleSampling,
    vehicleSamplingOtherTypes,
    populationSampling,
    circleFilter,
    buildTrackPersonScript,
    vehicleIdPrefix
  )

  def trackPerson(
    sourcePath: String,
    networkPath: String,
    personId: String,
    idPrefix: String
  ): RunConfig =
    defaultValues(
      sourcePath,
      networkPath,
      viaEventsPath = sourcePath + ".track.via.events.xml",
      viaFollowPersonScriptPath = sourcePath + ".track.via.js.txt",
      viaIdGoupsFilePath = sourcePath + ".track.via.ids.txt",
      populationSampling = Seq(PopulationSample(1, _ == personId)),
      buildTrackPersonScript = true,
      vehicleIdPrefix = idPrefix
    )

  def filterPopulation(
    sourcePath: String,
    networkPath: String,
    idPrefix: String,
    populationSamples: Seq[PopulationSample],
    circleFilter: Seq[Circle] = Seq.empty[Circle],
    viaEventsFileSuffix: String = "",
  ): RunConfig =
    defaultValues(
      sourcePath,
      networkPath,
      viaEventsPath = sourcePath + ".via.events." + viaEventsFileSuffix + ".xml",
      viaIdGoupsFilePath = sourcePath + ".via.ids." + viaEventsFileSuffix + ".txt",
      populationSampling = populationSamples,
      circleFilter = circleFilter,
      vehicleIdPrefix = idPrefix
    )

  def filterVehicles(
    sourcePath: String,
    networkPath: String,
    idPrefix: String,
    vehiclesSamples: Seq[VehicleSample] = Seq.empty[VehicleSample],
    vehiclesSamplesOtherTypes: Double = 1.0,
    circleFilter: Seq[Circle] = Seq.empty[Circle],
    viaEventsFileSuffix: String = "",
    excludedVehicleIds: Seq[String] = Seq.empty[String],
  ): RunConfig =
    defaultValues(
      sourcePath,
      networkPath,
      viaEventsPath = sourcePath + ".via.events." + viaEventsFileSuffix + ".xml",
      viaIdGoupsFilePath = sourcePath + ".via.ids." + viaEventsFileSuffix + ".txt",
      excludedVehicleIds = excludedVehicleIds,
      vehicleSampling = vehiclesSamples,
      vehicleSamplingOtherTypes = vehiclesSamplesOtherTypes,
      circleFilter = circleFilter,
      vehicleIdPrefix = idPrefix
    )
}
