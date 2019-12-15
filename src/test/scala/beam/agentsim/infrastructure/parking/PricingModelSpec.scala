package beam.agentsim.infrastructure.parking
import org.scalatest.{Matchers, WordSpec}

class PricingModelSpec extends WordSpec with Matchers {
  "PricingModel" when {
    ".apply()" should {
      "throw an exception when an invalid pricing model is provided" in {
        an[java.io.IOException] should be thrownBy PricingModel("foo", "1")
      }
      "throw an exception with negative cost values" in {
        an[IllegalArgumentException] should be thrownBy PricingModel("flatfee", "-2")
      }
      "construct a flatfee pricing model" in {
        val inputCost = 100 // $1.00
        val duration = 7200 // 2 hours
        PricingModel("flatfee", inputCost.toString) match {
          case Some(PricingModel.FlatFee(cost)) =>
            cost should equal(inputCost)
            PricingModel.evaluateParkingTicket(PricingModel.FlatFee(cost), duration) should equal(
              inputCost
            )
          case _ => fail()
        }
      }
      "construct a block pricing model" in {
        val inputCost = 100 // $1.00
        val duration = 7200 // 2 hours
        PricingModel("block", inputCost.toString) match {
          case Some(PricingModel.Block(cost, intervalInSeconds)) =>
            cost should equal(100)
            intervalInSeconds should equal(PricingModel.DefaultPricingInterval)
            PricingModel.evaluateParkingTicket(PricingModel.Block(cost, intervalInSeconds), duration) should equal(
              inputCost * 2
            )
          case _ => fail()
        }
      }
    }
    "Block Pricing" should {
      "correctly calculate the increase of cost by blocks of seconds" in {
        val inputCost = 100 // $1.00
        for {
          blockIntervalInSeconds <- Seq(900, 1800, 3600) // 15,30,and 60-minute intervals
          parkingDuration        <- 900 to 7200 by 900 // parking durations from 15m to 2h by 15m
        } {
          PricingModel("block", inputCost.toString, blockIntervalInSeconds.toString) match {
            case Some(PricingModel.Block(cost, intervalInSeconds)) =>
              cost should equal(inputCost)
              intervalInSeconds should equal(blockIntervalInSeconds)

              val expectedTicketPrice
                : Double = inputCost.toDouble * (parkingDuration.toDouble / blockIntervalInSeconds.toDouble)
              PricingModel.evaluateParkingTicket(PricingModel.Block(cost, intervalInSeconds), parkingDuration) should equal(
                expectedTicketPrice
              )
            case _ => fail()
          }
        }
      }
    }
  }
}
