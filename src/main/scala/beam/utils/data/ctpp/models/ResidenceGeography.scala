package beam.utils.data.ctpp.models

sealed trait ResidenceGeography {
  def name: String

  /** CTPP Summary Level
    */
  def level: String
}

object ResidenceGeography {
  case object Nation extends ResidenceGeography {
    override def name: String = "Nation (US Total)"
    override def level: String = "C01"
  }
  case object State extends ResidenceGeography {
    override def name: String = "State"
    override def level: String = "C02"
  }
  case object StateCounty extends ResidenceGeography {
    override def name: String = "State-County"
    override def level: String = "C03"
  }
  case object StateCountyMCD extends ResidenceGeography {
    override def name: String = "State-County-MCD"
    override def level: String = "C04"
  }
  case object StatePlace extends ResidenceGeography {
    override def name: String = "State-Place"
    override def level: String = "C05"
  }

  case object MetropolitanStatisticalArea extends ResidenceGeography {
    override def name: String = "Metropolitan Statistical Area"
    override def level: String = "C07"
  }

  case object MetropolitanStatisticalAreaEACHPrincipalCity extends ResidenceGeography {
    override def name: String = "Metropolitan Statistical Area - EACH Principal City"
    override def level: String = "C08"
  }

  case object StatePUMA5 extends ResidenceGeography {
    override def name: String = "State-PUMA5"
    override def level: String = "C09"
  }

  case object StateCountyTract extends ResidenceGeography {
    override def name: String = "State-County-Tract"
    override def level: String = "C11"
  }

  case object TAD extends ResidenceGeography {
    override def name: String = "TAD (does not include PR)"
    override def level: String = "C12"
  }

  case object TAZ extends ResidenceGeography {
    override def name: String = "TAZ (does not include PR)"
    override def level: String = "C13"
  }
}
