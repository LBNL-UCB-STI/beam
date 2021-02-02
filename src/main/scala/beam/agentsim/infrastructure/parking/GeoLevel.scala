package beam.agentsim.infrastructure.parking

import beam.agentsim.infrastructure.parking.ParkingStallSampling.GeoSampling
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.network.Link

import scala.util.Random

trait GeoLevel[A] {
  def getId(a: A): Id[A]
  def centroidLocation(a: A): Coord
  def parseId(strId: String): Id[A]
  def emergencyGeoId: Id[A]
  def defaultGeoId: Id[A]
  def geoSampling: GeoSampling[A]
}

object GeoLevel {

  def apply[A](implicit gl: GeoLevel[A]): GeoLevel[A] = gl

  object ops {
    def getId[A: GeoLevel](a: A): Id[A] = GeoLevel[A].getId(a)

    implicit class GeoLevelOps[A](val a: A) extends AnyVal {
      def getId(implicit gl: GeoLevel[A]): Id[A] = gl.getId(a)
      def centroidLocation(implicit gl: GeoLevel[A]): Coord = gl.centroidLocation(a)
    }
  }

  implicit val tazGeoLevel: GeoLevel[TAZ] = new GeoLevel[TAZ] {
    override def getId(a: TAZ): Id[TAZ] = a.tazId

    override def parseId(strId: String): Id[TAZ] = Id.create(strId, classOf[TAZ])

    override def emergencyGeoId: Id[TAZ] = TAZ.EmergencyTAZId

    override def defaultGeoId: Id[TAZ] = TAZ.DefaultTAZId

    override def geoSampling: GeoSampling[TAZ] = ParkingStallSampling.availabilityAwareSampling

    override def centroidLocation(a: TAZ): Location = a.coord
  }

  implicit val linkGeoLevel: GeoLevel[Link] = new GeoLevel[Link] {
    override def getId(a: Link): Id[Link] = a.getId

    override def parseId(strId: String): Id[Link] = Id.create(strId, classOf[Link])

    override def emergencyGeoId: Id[Link] = LinkLevelOperations.EmergencyLinkId

    override def defaultGeoId: Id[Link] = LinkLevelOperations.DefaultLinkId

    override def geoSampling: GeoSampling[Link] = (_: Random, _: Location, link: Link, _: Double) => link.getCoord

    override def centroidLocation(a: Link): Location = a.getCoord
  }

  /**
    * This method can be used to get the special geo ids at runtime
    * @param geoLevel the geo level ("TAZ", "Link")
    * @return a tuple (emergency id, default id)
    */
  def getSpecialGeoIds(geoLevel: String): (Id[_], Id[_]) = {
    geoLevel.toLowerCase match {
      case "taz"  => (TAZ.EmergencyTAZId, TAZ.DefaultTAZId)
      case "link" => (LinkLevelOperations.EmergencyLinkId, LinkLevelOperations.DefaultLinkId)
      case _ =>
        throw new IllegalArgumentException(s"Unsupported parking level type $geoLevel, only TAZ | Link are supported")
    }
  }
}
