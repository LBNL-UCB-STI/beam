package beam.agentsim.agents.choice.mode

import beam.router.Modes.BeamMode.CAR
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

import scala.io.Source

/**
  * BEAM
  */
object BridgeTollDefaults {
  private var tollPrices: Map[Int, Double] = _

  def estimateBridgeFares(
    alternatives: Seq[EmbodiedBeamTrip],
    beamServices: BeamServices
  ): Seq[BigDecimal] = {

    val tollPriceFile = beamServices.beamConfig.beam.agentsim.toll.file
    if (tollPrices == null) tollPrices = readTollPrices(tollPriceFile)

    alternatives.map { alt =>
      alt.tripClassifier match {
        case CAR =>
          BigDecimal(
            alt.toBeamTrip.legs.map { beamLeg =>
              if (beamLeg.mode.toString.equalsIgnoreCase("CAR")) {
                beamLeg.travelPath.linkIds.filter(tollPrices.contains).map(tollPrices).sum
              } else {
                0
              }
            }.sum
          )
        case _ =>
          BigDecimal(0)
      }
    }
  }

  private def readTollPrices(tollPricesFile: String): Map[Int, Double] = {
    Source
      .fromFile(tollPricesFile)
      .getLines()
      .map(_.split(","))
      .filterNot(_(0).equalsIgnoreCase("linkId"))
      .map(t => t(0).toInt -> t(1).toDouble)
      .toMap
  }
}
