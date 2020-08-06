package beam.utils.data.ctpp.models

import scala.util.{Failure, Success, Try}

sealed trait Industry {
  def lineNumber: Int
  def name: String
}

object Industry {

  val all: Vector[Industry] =
    Vector(Agriculture, Manufacturing, WholesaleTrade, Information, Educational, Arts, OtherServices)

  def apply(lineNumber: Int): Try[Industry] = {
    all.find(x => x.lineNumber == lineNumber) match {
      case Some(value) => Success(value)
      case None =>
        Failure(new IllegalStateException(s"Could not find `Industry` with lineNumber = $lineNumber"))
    }
  }

  case object Agriculture extends Industry {
    val lineNumber: Int = 2
    val name: String = "Agriculture, forestry, fishing and hunting, and mining; + Construction; + Armed Forces"
  }
  case object Manufacturing extends Industry {
    val lineNumber: Int = 3

    val name: String = "Manufacturing"
  }
  case object WholesaleTrade extends Industry {
    val lineNumber: Int = 4

    val name: String = "Wholesale trade; + Retail Trade; + Transportation and warehousing, and utilities"
  }
  case object Information extends Industry {
    val lineNumber: Int = 5

    val name: String =
      "Information; + Finance, insurance, real estate and rental and leasing; + Professional, scientific, management, administrative, and waste management services"
  }
  case object Educational extends Industry {
    val lineNumber: Int = 6

    val name: String = "Educational, health and social services"
  }
  case object Arts extends Industry {
    val lineNumber: Int = 7

    val name: String = "Arts, entertainment, recreation, accommodation and food services"
  }
  case object OtherServices extends Industry {
    val lineNumber: Int = 8

    val name: String = "Other services (except public administration); + Public Administration"
  }
}
