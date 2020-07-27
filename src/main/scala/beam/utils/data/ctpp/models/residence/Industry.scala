package beam.utils.data.ctpp.models.residence

import scala.util.{Failure, Success, Try}

sealed trait Industry {
  def lineNumber: Int
  def name: String
}

/*
TableShell(A102214,2,2,Agriculture, forestry, fishing and hunting, and mining)
TableShell(A102214,3,2,Construction)
TableShell(A102214,4,2,Manufacturing)
TableShell(A102214,5,2,Wholesale trade)
TableShell(A102214,6,2,Retail trade)
TableShell(A102214,7,2,Transportation and warehousing, and utilities)
TableShell(A102214,8,2,Information)
TableShell(A102214,9,2,Finance, insurance, real estate and rental and leasing)
TableShell(A102214,10,2,Professional, scientific, management, administrative,  and waste management services)
TableShell(A102214,11,2,Educational, health and social services)
TableShell(A102214,12,2,Arts, entertainment, recreation, accommodation and food services)
TableShell(A102214,13,2,Other services (except public administration))
TableShell(A102214,14,2,Public administration)
TableShell(A102214,15,2,Armed forces)
*/
object Industry {

  val all: Vector[Industry] =
    Vector(Agriculture, Construction, Manufacturing, WholesaleTrade, RetailTrade, Transportation, Information, Finance, Professional,
      Educational, Arts, OtherServices, PublicAdministration, ArmedForces)

  require(all.size == 15-2+1)

  def apply(lineNumber: Int): Try[Industry] = {
    all.find(x => x.lineNumber == lineNumber) match {
      case Some(value) => Success(value)
      case None =>
        Failure(new IllegalStateException(s"Could not find `Industry` with lineNumber = $lineNumber"))
    }
  }

  case object Agriculture extends Industry {
    val lineNumber: Int = 2
    val name: String = "Agriculture, forestry, fishing and hunting, and mining"
  }
  case object Construction extends Industry {
    val lineNumber: Int = 3

    val name: String = "Construction"
  }
  case object Manufacturing extends Industry {
    val lineNumber: Int = 4

    val name: String = "Manufacturing"
  }
  case object WholesaleTrade extends Industry {
    val lineNumber: Int = 5

    val name: String = "Wholesale trade"
  }
  case object RetailTrade extends Industry {
    val lineNumber: Int = 6

    val name: String = "Retail trade"
  }
  case object Transportation extends Industry {
    val lineNumber: Int = 7

    val name: String = "Transportation and warehousing, and utilities"
  }
  case object Information extends Industry {
    val lineNumber: Int = 8

    val name: String = "Information"
  }
  case object Finance extends Industry {
    val lineNumber: Int = 9

    val name: String = "Finance, insurance, real estate and rental and leasing"
  }
  case object Professional extends Industry {
    val lineNumber: Int = 10

    val name: String = "Professional, scientific, management, administrative,  and waste management services"
  }
  case object Educational extends Industry {
    val lineNumber: Int = 11

    val name: String = "Educational, health and social services"
  }
  case object Arts extends Industry {
    val lineNumber: Int = 12

    val name: String = "Arts, entertainment, recreation, accommodation and food services"
  }
  case object OtherServices extends Industry {
    val lineNumber: Int = 13

    val name: String = "Other services (except public administration)"
  }
  case object PublicAdministration extends Industry {
    val lineNumber: Int = 14

    val name: String = "Public administration"
  }
  case object ArmedForces extends Industry {
    val lineNumber: Int = 15

    val name: String = "Armed forces"
  }
}
