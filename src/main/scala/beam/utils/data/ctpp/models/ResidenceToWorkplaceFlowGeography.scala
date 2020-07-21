package beam.utils.data.ctpp.models

sealed trait ResidenceToWorkplaceFlowGeography {

  /** From Residence Geography
    */
  def from: String

  /** To Residence Geography
    */
  def to: String

  /** CTPP Summary Level
    */
  def level: String
}

object ResidenceToWorkplaceFlowGeography {
  case object `State To State` extends ResidenceToWorkplaceFlowGeography {
    override def from: String = "State"
    override def to: String = "State"
    override def level: String = "C42"
  }
  case object `State-County To State-County` extends ResidenceToWorkplaceFlowGeography {
    override def from: String = "State-County"
    override def to: String = "State-County"
    override def level: String = "C43"
  }
  case object `State-County-MCD To State-County-MCD` extends ResidenceToWorkplaceFlowGeography {
    override def from: String = "State-County-MCD"
    override def to: String = "State-County-MCD"
    override def level: String = "C44"
  }
  case object `State-Place To State-Place` extends ResidenceToWorkplaceFlowGeography {
    override def from: String = "State-Place"
    override def to: String = "State-Place"
    override def level: String = "C45"
  }

  case object `Metropolitan Statistical Area To Metropolitan Statistical Area`
      extends ResidenceToWorkplaceFlowGeography {
    override def from: String = "Metropolitan Statistical Area - EACH Principal City"
    override def to: String = "Metropolitan Statistical Area - EACH Principal City"
    override def level: String = "C48"
  }

  case object `PUMA5 To POWPUMA` extends ResidenceToWorkplaceFlowGeography {
    override def from: String = "PUMA5"
    override def to: String = "POWPUMA"
    override def level: String = "C49"
  }

  case object `TAZ To TAZ` extends ResidenceToWorkplaceFlowGeography {
    override def from: String = "TAZ"
    override def to: String = "TAZ"
    override def level: String = "C56"
  }
}
