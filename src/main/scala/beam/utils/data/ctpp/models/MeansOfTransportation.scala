package beam.utils.data.ctpp.models

import beam.router.Modes.BeamMode

import scala.util.{Failure, Success, Try}

sealed trait MeansOfTransportation {
  def lineNumber: Int

  def toBeamMode: Option[BeamMode] = {
    this match {
      case MeansOfTransportation.`Car, truck, or van -- Drove alone`                   => Some(BeamMode.CAR)
      case MeansOfTransportation.`Car, truck, or van -- In a 2-person carpool`         => Some(BeamMode.CAR)
      case MeansOfTransportation.`Car, truck, or van -- In a 3-person carpool`         => Some(BeamMode.CAR)
      case MeansOfTransportation.`Car, truck, or van -- In a 4-person carpool`         => Some(BeamMode.CAR)
      case MeansOfTransportation.`Car, truck, or van -- In a 5-or-6-person carpool`    => Some(BeamMode.CAR)
      case MeansOfTransportation.`Car, truck, or van -- In a 7-or-more-person carpool` => Some(BeamMode.CAR)
      case MeansOfTransportation.`Bus or trolley bus`                                  => Some(BeamMode.TRANSIT)
      case MeansOfTransportation.`Streetcar or trolley car`                            => Some(BeamMode.TRANSIT)
      case MeansOfTransportation.`Subway or elevated`                                  => Some(BeamMode.TRANSIT)
      case MeansOfTransportation.`Railroad`                                            => Some(BeamMode.TRANSIT)
      case MeansOfTransportation.`Ferryboat`                                           => Some(BeamMode.TRANSIT)
      case MeansOfTransportation.`Bicycle`                                             => Some(BeamMode.BIKE)
      case MeansOfTransportation.`Walked`                                              => Some(BeamMode.WALK)
      case MeansOfTransportation.`Taxicab`                                             => Some(BeamMode.RIDE_HAIL)
      case MeansOfTransportation.`Motorcycle`                                          => None
      case MeansOfTransportation.`Other method`                                        => None
    }
  }
}

object MeansOfTransportation {

  val all: List[MeansOfTransportation] = List(
    `Car, truck, or van -- Drove alone`,
    `Car, truck, or van -- In a 2-person carpool`,
    `Car, truck, or van -- In a 3-person carpool`,
    `Car, truck, or van -- In a 4-person carpool`,
    `Car, truck, or van -- In a 5-or-6-person carpool`,
    `Car, truck, or van -- In a 7-or-more-person carpool`,
    `Bus or trolley bus`,
    `Streetcar or trolley car`,
    `Subway or elevated`,
    `Railroad`,
    `Ferryboat`,
    `Railroad`,
    `Bicycle`,
    `Walked`,
    `Taxicab`,
    `Motorcycle`,
    `Other method`
  )

  def apply(lineNumber: Int): Try[MeansOfTransportation] = {
    all.find(x => x.lineNumber == lineNumber) match {
      case Some(value) => Success(value)
      case None =>
        Failure(new IllegalStateException(s"Could not find `MeansOfTransportation` with lineNumber = $lineNumber"))
    }
  }

  case object `Car, truck, or van -- Drove alone` extends MeansOfTransportation {
    override def lineNumber: Int = 2
  }
  case object `Car, truck, or van -- In a 2-person carpool` extends MeansOfTransportation {
    override def lineNumber: Int = 3
  }
  case object `Car, truck, or van -- In a 3-person carpool` extends MeansOfTransportation {
    override def lineNumber: Int = 4
  }
  case object `Car, truck, or van -- In a 4-person carpool` extends MeansOfTransportation {
    override def lineNumber: Int = 5
  }
  case object `Car, truck, or van -- In a 5-or-6-person carpool` extends MeansOfTransportation {
    override def lineNumber: Int = 6
  }
  case object `Car, truck, or van -- In a 7-or-more-person carpool` extends MeansOfTransportation {
    override def lineNumber: Int = 7
  }
  case object `Bus or trolley bus` extends MeansOfTransportation {
    override def lineNumber: Int = 8
  }
  case object `Streetcar or trolley car` extends MeansOfTransportation {
    override def lineNumber: Int = 9
  }
  case object `Subway or elevated` extends MeansOfTransportation {
    override def lineNumber: Int = 10
  }
  case object `Railroad` extends MeansOfTransportation {
    override def lineNumber: Int = 11
  }
  case object `Ferryboat` extends MeansOfTransportation {
    override def lineNumber: Int = 12
  }
  case object `Bicycle` extends MeansOfTransportation {
    override def lineNumber: Int = 13
  }
  case object `Walked` extends MeansOfTransportation {
    override def lineNumber: Int = 14
  }
  case object `Taxicab` extends MeansOfTransportation {
    override def lineNumber: Int = 15
  }
  case object `Motorcycle` extends MeansOfTransportation {
    override def lineNumber: Int = 16
  }
  case object `Other method` extends MeansOfTransportation {
    override def lineNumber: Int = 17
  }
}
