package beam.utils.data.ctpp.models

sealed trait WorkedHours {
  def range: Range
  def name: String
}

object WorkedHours {

  case object `Usually worked 1 to 14 hours per week` extends WorkedHours {
    override def range: Range = Range.inclusive(1, 14)

    override def name: String = "Usually worked 1 to 14 hours per week"
  }

  case object `Usually worked 15 to 20 hours per week` extends WorkedHours {
    override def range: Range = Range.inclusive(15, 20)

    override def name: String = "Usually worked 15 to 20 hours per week"
  }

  case object `Usually worked 21 to 34 hours per week` extends WorkedHours {
    override def range: Range = Range.inclusive(21, 34)

    override def name: String = "Usually worked 21 to 34 hours per week"
  }

  case object `Usually worked 35 to 40 hours per week` extends WorkedHours {
    override def range: Range = Range.inclusive(35, 40)

    override def name: String = "Usually worked 35 to 40 hours per week"
  }

  case object `Usually worked 41 to 55 hours per week` extends WorkedHours {
    override def range: Range = Range.inclusive(41, 55)

    override def name: String = "Usually worked 41 to 55 hours per week"
  }

  case object `Usually worked 56 or more hours per week` extends WorkedHours {
    override def range: Range = Range.inclusive(56, 56)

    override def name: String = "Usually worked 56 or more hours per week"
  }
}
