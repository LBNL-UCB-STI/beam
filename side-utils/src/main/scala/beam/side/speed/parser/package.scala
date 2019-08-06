package beam.side.speed
import beam.side.speed.parser.composer.{BeamComposerInterpreters, MetricsComposerInterpreters}
import beam.side.speed.parser.operation.ProgramInterprets

package object parser {
  implicit object interpreter extends MetricsComposerInterpreters with ProgramInterprets with BeamComposerInterpreters
}
