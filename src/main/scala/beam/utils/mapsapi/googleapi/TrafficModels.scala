package beam.utils.mapsapi.googleapi

object TrafficModels {

  sealed abstract class TrafficModel(val apiString: String)

  case object BestGuess extends TrafficModel("best_guess")

  case object Optimistic extends TrafficModel("optimistic")

  case object Pessimistic extends TrafficModel("pessimistic")

}
