package beam.utils.beam_to_matsim.events_filter

case class PopulationSample(percentage: Double, personIsInteresting: String => Boolean)
