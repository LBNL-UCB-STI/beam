package beam.utils.data.ctpp.models

sealed trait HouseholdIncome {
  def range: Range
}

object HouseholdIncome {

  val all: List[HouseholdIncome] = List(
    Total,
    `Less than $15,000`,
    `$15,000-$24,999`,
    `$25,000-$34,999`,
    `$35,000-$49,999`,
    `$50,000-$74,999`,
    `$75,000-$99,999`,
    `$100,000-$149,999`,
    `$150,000 or more`
  )

  case object Total extends HouseholdIncome {
    override def range: Range = 0 to Int.MaxValue
  }
  case object `Less than $15,000` extends HouseholdIncome {
    override def range: Range = 0 until 15000
  }
  case object `$15,000-$24,999` extends HouseholdIncome {
    override def range: Range = 15000 to 24999
  }
  case object `$25,000-$34,999` extends HouseholdIncome {
    override def range: Range = 25000 to 34999
  }
  case object `$35,000-$49,999` extends HouseholdIncome {
    override def range: Range = 25000 to 34999
  }
  case object `$50,000-$74,999` extends HouseholdIncome {
    override def range: Range = 50000 to 74999
  }
  case object `$75,000-$99,999` extends HouseholdIncome {
    override def range: Range = 75000 to 99999
  }
  case object `$100,000-$149,999` extends HouseholdIncome {
    override def range: Range = 100000 to 149999
  }
  case object `$150,000 or more` extends HouseholdIncome {
    override def range: Range = 150000 to Int.MaxValue
  }
}
