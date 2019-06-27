package beam.utils.beamToVia

case class Circle(x: Double, y: Double, r: Double)

case class VehicleSample(vehicleType: String, percentage: Double)

/**
  * To gather certain percent of population among actors with filtered Id's
  * @param percentage - 1.0 equals to 100%
  * @param personIsInteresting
  */
case class PopulationSample(percentage: Double, personIsInteresting: String => Boolean)

case class RunConfig(
  beamEventsPath: String,
  networkPath: String,
  viaEventsPath: String,
  viaIdGoupsFilePath: String,
  viaIdGoupsDirectoryPath: String,
  viaRunScriptPath: String,
  vehicleSampling: Seq[VehicleSample],
  populationSampling: Seq[PopulationSample],
  circleFilter: Seq[Circle]
)

object RunConfig {

  def apply(
    beamEventsPath: String,
    networkPath: String,
    viaEventsPath: String,
    viaIdGoupsFilePath: String,
    viaIdGoupsDirectoryPath: String,
    viaRunScriptPath: String,
    vehicleSampling: Seq[VehicleSample],
    populationSampling: Seq[PopulationSample],
    circleFilter: Seq[Circle]
  ): RunConfig =
    new RunConfig(
      beamEventsPath,
      networkPath,
      viaEventsPath,
      viaIdGoupsFilePath,
      viaIdGoupsDirectoryPath,
      viaRunScriptPath,
      vehicleSampling,
      populationSampling,
      circleFilter
    )

  def defaultValues(
    sourcePath: String,
    networkPath: String = "",
    viaEventsPath: String = "",
    viaIdGoupsFilePath: String = "",
    viaIdGoupsDirectoryPath: String = "",
    viaRunScriptPath: String = "",
    vehicleSampling: Seq[VehicleSample] = Seq.empty[VehicleSample],
    populationSampling: Seq[PopulationSample] = Seq.empty[PopulationSample],
    circleFilter: Seq[Circle] = Seq.empty[Circle]
  ): RunConfig = RunConfig(
    sourcePath,
    networkPath,
    if (viaEventsPath.isEmpty) sourcePath + ".via.events.xml" else viaEventsPath,
    if (viaIdGoupsFilePath.isEmpty) sourcePath + ".via.ids.txt" else viaIdGoupsFilePath,
    if (viaIdGoupsDirectoryPath.isEmpty) sourcePath + ".via.ids" else viaIdGoupsDirectoryPath,
    viaRunScriptPath,
    vehicleSampling,
    populationSampling,
    circleFilter
  )

  def filterPopulation(sourcePath: String, populationSamples: Seq[PopulationSample]): RunConfig =
    defaultValues(sourcePath, populationSampling = populationSamples)

  def filterVehicles(sourcePath: String, vehiclesSamples: Seq[VehicleSample]): RunConfig =
    defaultValues(sourcePath, vehicleSampling = vehiclesSamples)

  def withoutFiltering(sourcePath: String): RunConfig = defaultValues(sourcePath)
}
