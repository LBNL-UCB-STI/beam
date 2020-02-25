package beam.utils.data.ctpp.models

sealed trait HouseholdIncome {
  def contains(income: Int): Boolean
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
    override def contains(income: Int): Boolean = false
  }
  case object `Less than $15,000` extends HouseholdIncome {
    override def contains(income: Int): Boolean = income < 15000

  }
  case object `$15,000-$24,999` extends HouseholdIncome {
    override def contains(income: Int): Boolean = income >= 15000 && income <= 24999
  }
  case object `$25,000-$34,999` extends HouseholdIncome {
    override def contains(income: Int): Boolean = income >= 25000 && income <= 34999
  }
  case object `$35,000-$49,999` extends HouseholdIncome {
    override def contains(income: Int): Boolean = income >= 35000 && income <= 49999
  }
  case object `$50,000-$74,999` extends HouseholdIncome {
    override def contains(income: Int): Boolean = income >= 50000 && income <= 74999
  }
  case object `$75,000-$99,999` extends HouseholdIncome {
    override def contains(income: Int): Boolean = income >= 75000 && income <= 99999
  }
  case object `$100,000-$149,999` extends HouseholdIncome {
    override def contains(income: Int): Boolean = income >= 100000 && income <= 149999
  }
  case object `$150,000 or more` extends HouseholdIncome {
    override def contains(income: Int): Boolean = income >= 150000
  }
}
