package beam.playground.sid

/**
  * Created by sfeygin on 3/29/17.
  */
trait BeamException extends Exception
sealed trait BeamSupervisionException extends BeamException

object BeamExceptions {
  case class BeamAgentRestartException() extends BeamSupervisionException
  case class BeamAgentResumeException() extends BeamSupervisionException
  case class BeamAgentStopException() extends BeamSupervisionException
}
