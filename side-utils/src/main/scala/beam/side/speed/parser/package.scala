package beam.side.speed
import beam.side.speed.parser.composer.MetricsComposerInterprets
import beam.side.speed.parser.operation.ProgramInterprets

package object parser {
  implicit object interpreter extends MetricsComposerInterprets with ProgramInterprets {}
}
