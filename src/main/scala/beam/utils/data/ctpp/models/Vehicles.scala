package beam.utils.data.ctpp.models

import scala.util.{Failure, Success, Try}

sealed trait Vehicles {
  def lineNumber: Int
  def desc: String
  def count: Int
}

object Vehicles {

  private val allVehicles: List[Vehicles] = List(
    Total,
    `0 vehicles`,
    `1 vehicle`,
    `2 vehicles`,
    `3 vehicles`,
    `4-or-more vehicles`
  )

  def apply(lineNumber: Int): Try[Vehicles] = {
    allVehicles.find(x => x.lineNumber == lineNumber) match {
      case Some(value) => Success(value)
      case None =>
        Failure(new IllegalStateException(s"Could not find vehicle with lineNumber = $lineNumber"))
    }

  }

  case object Total extends Vehicles {
    override def lineNumber: Int = 1

    override def desc: String = "Total"

    override def count: Int = -1
  }

  case object `0 vehicles` extends Vehicles {
    override def lineNumber: Int = 2

    override def desc: String = "0 vehicles"

    override def count: Int = 0
  }

  case object `1 vehicle` extends Vehicles {
    override def lineNumber: Int = 3

    override def desc: String = "1 vehicles"

    override def count: Int = 1
  }

  case object `2 vehicles` extends Vehicles {
    override def lineNumber: Int = 4

    override def desc: String = "2 vehicles"

    override def count: Int = 2
  }

  case object `3 vehicles` extends Vehicles {
    override def lineNumber: Int = 5

    override def desc: String = "3 vehicles"

    override def count: Int = 3
  }

  case object `4-or-more vehicles` extends Vehicles {
    override def lineNumber: Int = 6

    override def desc: String = "4-or-more vehicles"

    override def count: Int = 4
  }
}
