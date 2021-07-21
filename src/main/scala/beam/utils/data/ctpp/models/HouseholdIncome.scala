package beam.utils.data.ctpp.models

sealed trait HouseholdIncome {
  def contains(income: Int): Boolean = range.contains(income)

  def distance(income: Int): Int = {
    val sDiff = Math.abs(income - range.start)
    val eDiff = Math.abs(income - range.end)
    Math.min(sDiff, eDiff)
  }

  val range: Range
}

object HouseholdIncome {

  def calcDistance(n: Int, rng: Range): Int = {
    val sDiff = Math.abs(n - rng.start)
    val eDiff = Math.abs(n - rng.end)
    Math.min(sDiff, eDiff)
  }

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

    override def distance(income: Int): Int = -1

    override val range: Range = Range(0, 1)
  }

  case object `Less than $15,000` extends HouseholdIncome {
    override val range: Range = Range(0, 15000)
  }

  case object `$15,000-$24,999` extends HouseholdIncome {
    override val range: Range = Range(0, 15000)
    override def contains(income: Int): Boolean = income >= 15000 && income <= 24999
  }

  case object `$25,000-$34,999` extends HouseholdIncome {
    override val range: Range = Range.inclusive(25000, 34999)
  }

  case object `$35,000-$49,999` extends HouseholdIncome {
    override val range: Range = Range.inclusive(35000, 49999)
  }

  case object `$50,000-$74,999` extends HouseholdIncome {
    override val range: Range = Range.inclusive(50000, 74999)
  }

  case object `$75,000-$99,999` extends HouseholdIncome {
    override val range: Range = Range.inclusive(75000, 99999)
  }

  case object `$100,000-$149,999` extends HouseholdIncome {
    override val range: Range = Range.inclusive(100000, 149999)
  }

  case object `$150,000 or more` extends HouseholdIncome {
    override val range: Range = Range.inclusive(150000, 150000)
    override def contains(income: Int): Boolean = income >= range.start
  }
}
