package beam.agentsim.infrastructure.parking

import scala.util.{Failure, Success, Try}

/**
  * A ParkingZone may have a PricingModel, which is used to calculate the currency cost for parking in that zone
  */
sealed trait PricingModel {
  def costInDollars: Double
}

object PricingModel {

  /**
    * A flat parking fee, such as an all-day rate at a parking garage
    * @param costInDollars the all-day rate, in dollars
    */
  case class FlatFee(costInDollars: Double) extends PricingModel {
    override def toString: String = "FlatFee"
  }

  /**
    * A parking fee that occurs over block intervals, such as an hourly parking meter
    * @param costInDollars the cost per interval
    * @param intervalSeconds the duration of the charging interval
    */
  case class Block(costInDollars: Double, intervalSeconds: Int) extends PricingModel {
    override def toString: String = "Block"
  }

  /**
    * 1 hour is the default interval
    */
  val DefaultPricingInterval: Int = 3600

  /**
    * construct an optional PricingModel based on a parsed input string
    * @param s the input string, scraped from a configuration file
    * @return an optional PricingModel if the input is recognized, otherwise None
    */
  def apply(
    s: String,
    costInDollars: String,
    intervalSeconds: String = DefaultPricingInterval.toString
  ): Option[PricingModel] =
    s.toLowerCase match {

      case "" => None

      case "flatfee" =>
        val costInDollarsDouble = parseFee(costInDollars, s)
//        val intervalInt = parseInterval(intervalSeconds, s)
        Some(FlatFee(costInDollarsDouble))

      case "block" =>
        val costInDollarsDouble = parseFee(costInDollars, s)
        val intervalInt = parseInterval(intervalSeconds, s)
        Some(Block(costInDollarsDouble, intervalInt))

      case _ =>
        throw new java.io.IOException(s"Pricing Model is invalid: $s")
    }

  /**
    * computes the cost of this pricing model for some duration. only considers the PricingModel, and does not include any fueling costs
    * @param pricingModel the pricing model
    * @param parkingDurationInSeconds duration of parking in seconds
    * @return monetary cost of parking, in cents
    */
  def evaluateParkingTicket(pricingModel: PricingModel, parkingDurationInSeconds: Int): Double = {
    pricingModel match {
      case FlatFee(costInDollars) => costInDollars
      case Block(costInDollars, intervalSeconds) =>
        (math.max(0.0, parkingDurationInSeconds.toDouble) / intervalSeconds.toDouble) * costInDollars
    }
  }

  /**
    * helper function that converts a value to an integer
    * @param valueString the text value taken from an input file
    * @param model the name of the ADT that this value is associated with
    * @return an integer, or throw an IllegalArgumentException
    */
  def parseInterval(valueString: String, model: String): Int = {
    Try {
      valueString.toDouble.toInt // catches decimal entries as well
    } match {
      case Failure(_) =>
        throw new IllegalArgumentException(
          s"could not parse $model parking attribute $valueString to an Integer"
        )
      case Success(valueInt) =>
        if (valueInt < 0)
          throw new IllegalArgumentException(
            s"negative time interval of $valueInt not allowed for PricingModel"
          )
        else valueInt
    }
  }

  /**
    * helper function that converts a value to an integer
    * @param valueString the text value taken from an input file
    * @param model the name of the ADT that this value is associated with
    * @return an integer, or throw an IllegalArgumentException
    */
  def parseFee(valueString: String, model: String): Double = {
    Try {
      valueString.toDouble // catches decimal entries as well
    } match {
      case Failure(_) =>
        throw new IllegalArgumentException(
          s"could not parse $model parking attribute $valueString to a Double"
        )
      case Success(valueDouble) =>
        if (valueDouble < 0)
          throw new IllegalArgumentException(
            s"negative pricing model feeInCents of $valueDouble not allowed for PricingModel"
          )
        else valueDouble
    }
  }
}
