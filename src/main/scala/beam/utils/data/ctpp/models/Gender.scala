package beam.utils.data.ctpp.models

import scala.util.{Failure, Success, Try}

sealed trait Gender {
  def lineNumber: Int
}

object Gender {

  def apply(lineNumber: Int): Try[Gender] = {
    if (lineNumber == 2) Success(Gender.Male)
    else if (lineNumber == 3) Success(Gender.Female)
    else Failure(new IllegalStateException(s"Could not find age with lineNumber = $lineNumber"))

  }

  case object Male extends Gender {
    override def lineNumber: Int = 2
  }
  case object Female extends Gender {
    override def lineNumber: Int = 3
  }
}
