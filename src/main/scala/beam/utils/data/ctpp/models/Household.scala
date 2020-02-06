package beam.utils.data.ctpp.models

sealed trait Household {
  def count: Int

  def desc: String
}

object Household {
  case object `1-person household` extends Household {
    override def count: Int = 1

    override def desc: String = "1-person household"
  }

  case object `2-person household` extends Household {
    override def count: Int = 2

    override def desc: String = "2-person household"
  }

  case object `3-person household` extends Household {
    override def count: Int = 3

    override def desc: String = "3-person household"
  }

  case object `4-or-more-person household` extends Household {
    override def count: Int = 4

    override def desc: String = "4-or-more-person household"
  }
}
