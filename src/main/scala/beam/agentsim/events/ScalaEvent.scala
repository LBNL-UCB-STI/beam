package beam.agentsim.events

/**
  * Base trait for Events defined in Scala.
  *
  * Events need to inherit this trait to avoid errors where BeamEventsWriterCSV complains about unknown attributes.
  */
trait ScalaEvent {}
