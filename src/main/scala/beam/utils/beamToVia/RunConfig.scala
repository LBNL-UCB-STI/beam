package beam.utils.beamToVia

case class Circle(x: Double, y: Double, r: Double)

case class VehicleSample(vehicleType: String, percentage: Double)

/**
  * To gather certain percent of population among actors with filtered Id's
  * @param percentage
  * @param personFilter
  */
case class PopulationSample(percentage: Double, personFilter: String => Boolean)

case class RunConfig(
  beamEventsPath: String,
  networkPath: String,
  viaEventsPath: String,
  viaIdGoupsPath: String,
  viaRunScriptPath: String,
  vehicleSampling: Seq[VehicleSample],
  populationSampling: Seq[PopulationSample],
  circleFilter: Seq[Circle]
)
