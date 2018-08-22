package beam.agentsim.agents.choice.mode

import java.nio.file.{Files, Paths}

import beam.router.Modes.BeamMode.CAR
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices
import beam.utils.FileUtils

import scala.io.Source

/**
  * BEAM
  */
// TODO This should be class, not an object
object BridgeTollDefaults {
  private var tollPrices: Map[Int, Double] = _ // TODO when it will be class, we can avoid this!

  def estimateBridgeFares(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    beamServices: BeamServices
  ): IndexedSeq[BigDecimal] = {

    val tollPriceFile = beamServices.beamConfig.beam.agentsim.toll.file
    if (tollPrices == null) tollPrices = readTollPrices(tollPriceFile)

    alternatives.map { alt =>
      alt.tripClassifier match {
        case CAR =>
          BigDecimal(
            alt.legs.view
              .map(_.beamLeg)
              .map { beamLeg =>
                if (beamLeg.mode.toString.equalsIgnoreCase("CAR")) {
                  beamLeg.travelPath.linkIds.view.filter(tollPrices.contains).map(tollPrices).sum
                } else {
                  0
                }
              }
              .sum
          )
        case _ =>
          BigDecimal(0)
      }
    }
  }

  private def readTollPrices(tollPricesFile: String): Map[Int, Double] = {
    if (Files.exists(Paths.get(tollPricesFile))) {
      Source
        .fromFile(tollPricesFile)
        .getLines()
        .map(_.split(","))
        .filterNot(_(0).equalsIgnoreCase("linkId"))
        .map(t => t(0).toInt -> t(1).toDouble)
        .toMap

    } else {
      Map()
    }
  }
}
