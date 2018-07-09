package beam.agentsim.agents.choice.mode

import beam.router.Modes.BeamMode.CAR
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

import scala.io.Source

/**
  * BEAM
  */
object BridgeTollDefaults {
//  val tollPricesBeamVille: Map[Int, Double] = Map(
//    1 -> 1,
//    200 -> 1
//  )
//
//  // source: https://www.transit.wiki/
//  val tollPricesSFBay: Map[Int, Double] = Map(
//    1191692 -> 5,
//    502 -> 5,
//    998142 -> 5,
//    722556 -> 5,
//    1523426 -> 5,
//    1053032 -> 5,
//    1457468 -> 7,
//    668214 -> 5
//  )

  private var tollPrices: Map[Int, Double] = _

  def estimateBridgeFares(alternatives: Seq[EmbodiedBeamTrip], beamServices: BeamServices): Seq[BigDecimal] = {
//    var tollPrices: Map[Int, Double] = Map()
    val tollPriceFile = beamServices.beamConfig.beam.agentsim.toll.file
    if(tollPrices == null) tollPrices = readTollPrices(tollPriceFile)
//    if (beamServices.beamConfig.beam.agentsim.simulationName.equalsIgnoreCase("beamville")) {
//      tollPrices = tollPricesBeamVille
//    } else {
//      tollPrices = tollPricesSFBay
//    }

    alternatives.map { alt =>
      alt.tripClassifier match {
        case CAR =>
          BigDecimal(alt.toBeamTrip().legs.map { beamLeg =>
            if (beamLeg.mode.toString.equalsIgnoreCase("CAR")) {
              beamLeg.travelPath.linkIds.filter(tollPrices.contains).map(tollPrices).sum
            } else {
              0
            }
          }.sum)
        case _ =>
          BigDecimal(0)
      }
    }
  }

  private def readTollPrices(tollPricesFile: String): Map[Int, Double] = {
    Source.fromFile(tollPricesFile).getLines().map(_.split(","))
      .filterNot(_(0).equalsIgnoreCase("linkId"))
      .map(t => t(0).toInt -> t(1).toDouble).toMap
  }
}
