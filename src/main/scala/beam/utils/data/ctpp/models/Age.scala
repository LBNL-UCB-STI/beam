package beam.utils.data.ctpp.models

import scala.util.{Failure, Success, Try}

sealed trait Age {
  def lineNumber: Int

  def desc: String

  def range: Range
}

object Age {
  private val allAges: List[Age] = List(
    `Under 16 years`,
    `16 and 17 years`,
    `18 to 20 years`,
    `21 to 24 years`,
    `25 to 34 years`,
    `35 to 44 years`,
    `45 to 59 years`,
    `60 to 64 years`,
    `65 to 74 years`,
    `75 years and over`
  )

  def apply(lineNumber: Int): Try[Age] = {
    allAges.find(x => x.lineNumber == lineNumber) match {
      case Some(value) => Success(value)
      case None =>
        Failure(new IllegalStateException(s"Could not find age with lineNumber = $lineNumber"))
    }

  }

  case object `Under 16 years` extends Age {
    override def lineNumber: Int = 2

    override def desc: String = "Under 16 years"

    override def range: Range = Range(0, 16)
  }

  case object `16 and 17 years` extends Age {
    override def lineNumber: Int = 3

    override def desc: String = "16 and 17 years"

    override def range: Range = Range.inclusive(16, 17)

  }

  case object `18 to 20 years` extends Age {
    override def lineNumber: Int = 4

    override def desc: String = "18 to 20 years"

    override def range: Range = Range.inclusive(18, 20)

  }

  case object `21 to 24 years` extends Age {
    override def lineNumber: Int = 5

    override def desc: String = "21 to 24 years"

    override def range: Range = Range.inclusive(21, 24)
  }

  case object `25 to 34 years` extends Age {
    override def lineNumber: Int = 6

    override def desc: String = "25 to 34 years"

    override def range: Range = Range.inclusive(25, 34)
  }

  case object `35 to 44 years` extends Age {
    override def lineNumber: Int = 7

    override def desc: String = "35 to 44 years"

    override def range: Range = Range.inclusive(35, 44)
  }

  case object `45 to 59 years` extends Age {
    override def lineNumber: Int = 8

    override def desc: String = "46 to 59 years"

    override def range: Range = Range.inclusive(45, 59)
  }

  case object `60 to 64 years` extends Age {
    override def lineNumber: Int = 9

    override def desc: String = "60 to 64 years"

    override def range: Range = Range.inclusive(60, 64)
  }

  case object `65 to 74 years` extends Age {
    override def lineNumber: Int = 10

    override def desc: String = "65 to 74 years"

    override def range: Range = Range.inclusive(65, 74)
  }

  case object `75 years and over` extends Age {
    override def lineNumber: Int = 11

    override def desc: String = "75 years and over"

    override def range: Range = Range.inclusive(75, 100)
  }

}
