package beam.router

import java.nio.file.Path
import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneOffset, ZonedDateTime}

import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.config.BeamConfig
import beam.utils.FileUtils
import org.matsim.api.core.v01.network.{Network, Link => MatsimLink, Node => MatsimNode}
import org.matsim.core.utils.io.IOUtils

import scala.collection.JavaConverters._
import scala.xml._

class MatsimNetworkToOsmConverter(beamConfig: BeamConfig) {
  private val uid = "1"
  private val user = "beam"
  private val version = "1"
  private val changeset = "1"
  val xmlEncode = "UTF-8"

  lazy val matsimNetwork: Network = {
    val networkCoordinator = DefaultNetworkCoordinator(beamConfig)
    networkCoordinator.init()
    networkCoordinator.network
  }

  def buildXmlFile(outputFile: Path): Path = {
    val xml = buildXml(ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime)
    XML.save(outputFile.toAbsolutePath.toString, xml, xmlEncode, xmlDecl = true, null)
    outputFile
  }

  def buildXml(timeStamp: LocalDateTime): Elem = {
    val zoned = timeStamp.atZone(ZoneOffset.UTC)
    val timeStampStr = DateTimeFormatter.ISO_INSTANT.format(zoned)
    val nodes = buildNodes(timeStampStr)
    val links = buildLinks(timeStampStr)
    <osm version='0.6' generator='BEAM'>
      {nodes}
      {links}
    </osm>
  }

  protected def buildNodes(timeStampStr: String): Seq[Elem] = {
    matsimNetwork.getNodes.asScala.map {
      case (_, link) => buildNode(timeStampStr, link)
    }.toSeq
  }

  protected def buildLinks(timeStampStr: String): Seq[Elem] = {
    matsimNetwork.getLinks.asScala.map {
      case (_, link) => buildLink(timeStampStr, link)
    }.toSeq
  }

  private def buildNode(timestamp: String, node: MatsimNode): Elem = {
    <node
    id={node.getId.toString}
    timestamp={timestamp}
    lat={node.getCoord.getY.toString}
    lon={node.getCoord.getX.toString}
    uid={uid} user={user}
    version={version}
    changeset={changeset}
    >
      <tag k='highway' v='traffic_signals' />
    </node>
  }

  private def buildLink(timestamp: String, link: MatsimLink): Elem = {
    <way
    id={link.getId.toString}
    timestamp={timestamp}
    uid={uid}
    user={user}
    version={version}
    changeset={changeset}
    >
      <nd ref={link.getFromNode.getId.toString} />
      <nd ref={link.getToNode.getId.toString} />
      <tag k='lanes' v='{link.getNumberOfLanes.toString}' />
      <tag k='maxspeed' v='{link.getFreespeed.toString}' />
      <tag k='capacity' v='{link.getCapacity.toString}' />
      <tag k='highway' v='trunk' />
      <tag k='name' v='col_0' />
      <tag k='oneway' v='yes' />
    </way>
  }
}