package beam.agentsim.infrastructure

import java.io.File

import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

import beam.sim.config.BeamConfig
import beam.utils.FileUtils
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.network.Network
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.NetworkReaderMatsimV2

object NetworkUtilsExtensions extends StrictLogging {

  def readNetwork(path: String): Network = {
    val network = NetworkUtils.createNetwork
    val reader = new NetworkReaderMatsimV2(network)
    reader.parse(FileUtils.getInputStream(path))
    network
  }

  def loadBikeLaneLinkIds(beamConfig: BeamConfig): Set[Int] = {
    Try {
      val result: Set[String] = {
        val bikeLaneLinkIdsPath: String = beamConfig.beam.routing.r5.bikeLaneLinkIdsFilePath
        if (new File(bikeLaneLinkIdsPath).isFile) {
          FileUtils.readAllLines(bikeLaneLinkIdsPath).toSet
        } else {
          Set.empty
        }
      }
      result.flatMap(str => Try(Some(str.toInt)).getOrElse(None))
    } match {
      case Failure(exception) =>
        logger.error("Could not load the bikeLaneLinkIds", exception)
        Set.empty
      case Success(value) => value
    }
  }

}
