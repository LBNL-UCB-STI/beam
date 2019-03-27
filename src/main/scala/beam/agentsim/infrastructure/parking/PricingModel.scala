package beam.agentsim.infrastructure.parking

import scala.util.{Failure, Success, Try}

/**
  * A ParkingZone may have a PricingModel, which is used to calculate the currency cost for parking in that zone
  */
sealed trait PricingModel {
  def cost: Int
}

object PricingModel {

  /**
    * A flat parking fee, such as an all-day rate at a parking garage
    * @param cost the all-day rate, in cents
    */
  case class FlatFee(cost: Int, intervalSeconds: Int) extends PricingModel {
    override def toString: String = "FlatFee"
  }

  /**
    * A parking fee that occurs over block intervals, such as an hourly parking meter
    * @param cost the cost per interval
    * @param intervalSeconds the duration of the charging interval
    */
  case class Block(cost: Int, intervalSeconds: Int) extends PricingModel {
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
    *
    */
  def apply(s: String, cost: String, intervalSeconds: String = DefaultPricingInterval.toString): Option[PricingModel] =
    s.toLowerCase match {

      case "" => None

      case "flatfee" =>
        val costInt = parseNumeric(cost, s)
        val intervalInt = parseNumeric(intervalSeconds, s)
        Some(FlatFee(costInt, intervalInt))

      case "block" =>
        val costInt = parseNumeric(cost, s)
        val intervalInt = parseNumeric(intervalSeconds, s)
        Some(Block(costInt, intervalInt))

      case _ =>
        throw new java.io.IOException(s"Pricing Model is invalid: $s")
    }

  /**
    * computes the cost of this pricing model for some duration. only considers the PricingModel, and does not include any fueling costs
    * @param pricingModel the pricing model
    * @param parkingDurationInSeconds duration of parking in seconds
    * @return monetary cost of parking
    */
  def evaluateParkingTicket(pricingModel: PricingModel, parkingDurationInSeconds: Int): Double = {
    pricingModel match {
      case FlatFee(cost, _)             => cost
      case Block(cost, intervalSeconds) => (parkingDurationInSeconds / intervalSeconds) * cost
    }
  }

  /**
    * helper function that converts a value to an integer
    * @param valueString the text value taken from an input file
    * @param model the name of the ADT that this value is associated with
    * @return an integer, or throw an IllegalArgumentException
    */
  def parseNumeric(valueString: String, model: String): Int = {
    Try {
      valueString.toDouble.toInt // catches decimal entries as well
    } match {
      case Failure(_) =>
        throw new IllegalArgumentException(
          s"could not parse $model parking attribute $valueString to an Integer."
        )
      case Success(valueInt) => valueInt
    }
  }
}
