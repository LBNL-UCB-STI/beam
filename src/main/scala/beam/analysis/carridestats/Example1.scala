package beam.analysis.carridestats

import java.net.URL
import java.util.concurrent.TimeUnit

object Example1 extends App {

  val inputFile = new URL(
    "https://beam-outputs.s3.amazonaws.com/output/austin/austin-prod-200k-cacc-enabled-flowCap0.13-60hours__2020-03-25_17-16-30_iwj/ITERS/it.10/10.CarRideStats.personal.csv.gz"
  )

  val requestMorning = CarRideStatsRequest(
    input = InputContentFromUrl(inputFile),
    sample = Some(CarRideStatsSample(sampleSize = 50, sampleSeed = Some(12345))),
    travelDistanceIntervalInMeters = Some(CarRideStatsTravelDistanceIntervalInMeters(3000, 15000)),
    departureTimeIntervalInSeconds = Some(CarRideStatsDepartureTimeIntervalInSeconds(28800, 32400)),
    areaBoundBox = Some(
      CarRideStatsFilterAreaBoundBox(CarRideStatsPoint(-97.843763, 30.481509), CarRideStatsPoint(-97.655786, 30.180919))
    )
  )

  val requestAfternoon = requestMorning.copy(
    departureTimeIntervalInSeconds = Some(CarRideStatsDepartureTimeIntervalInSeconds(61200, 64800))
  )

  println("Processing Started!")
  val process = CarRideStatsAdapter.generateGoogleMapsLinks(requestMorning)
  process.waitFor(2, TimeUnit.MINUTES)
  println("Processing Finished!")

}
