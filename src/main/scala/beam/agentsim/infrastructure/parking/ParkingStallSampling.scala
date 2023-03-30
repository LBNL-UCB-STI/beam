package beam.agentsim.infrastructure.parking

import scala.util.Random
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.jdk.CollectionConverters.collectionAsScalaIterableConverter
import scala.math.pow

/**
  * sampling methods for randomly generating stall locations from aggregate information
  */
object ParkingStallSampling extends LazyLogging {

  val maxOffsetDistance = 600.0 // TODO: Make this a config parameter

  def linkBasedSampling(
    rand: Random,
    requestLocation: Location,
    linkQuadTree: QuadTree[Link],
    distanceFunction: (Coord, Coord) => Double,
    availabilityRatio: Double,
    maxDist: Double = maxOffsetDistance
  ): Location = {
    val allLinks = linkQuadTree.getDisk(requestLocation.getX, requestLocation.getY, maxDist).asScala
    val totalLength = allLinks.foldRight(0.0)(_.getLength + _)
    var currentLength = 0.0
    val filteredLinks = rand.shuffle(allLinks).takeWhile { lnk =>
      currentLength += lnk.getLength
      currentLength <= totalLength * availabilityRatio
    }
    Some(filteredLinks)
      .filter(_.nonEmpty)
      .map(
        _.map(lnk => getClosestPointAlongLink(lnk, requestLocation, distanceFunction)).minBy(loc =>
          distanceFunction(loc, requestLocation)
        )
      )
      .getOrElse {
        logger.warn(s"Could not find a link for parking request at location: $requestLocation")
        requestLocation
      }
  }

  private def getClosestPointAlongLink(
    link: Link,
    requestLocation: Location,
    distanceFunction: (Coord, Coord) => Double
  ): Location = {
    val (p1, p2) = (link.getToNode.getCoord, link.getFromNode.getCoord)
    val (closestPointOption, closestDistance) = List(p1, p2).foldLeft(None: Option[Coord], Double.PositiveInfinity) {
      (accumulator, endPoint) =>
        val dist = distanceFunction(endPoint, requestLocation)
        if (dist < accumulator._2) {
          (Some(endPoint), dist)
        } else {
          accumulator
        }
    }

    closestPointOption match {
      case Some(closestPoint) =>
        if (closestDistance < 100) {
          closestPoint
        } else {
          val (dx, dy) = (p2.getX - p1.getX, p2.getY - p1.getY) // vector between p1 and p2
          val (diffx, diffy) =
            (requestLocation.getX - p1.getX, requestLocation.getY - p1.getY) // vector between p1 and p3
          val c1 = (dx * diffx + dy * diffy) / (pow(dx, 2) + pow(dy, 2)) // projection of w onto v
          if (c1 < 0) {
            p1
          } else if (c1 > 1) {
            p2
          } else {
            val x = p1.getX + c1 * dx // closest point on line to p3
            val y = p1.getY + c1 * dy // closest point on line to p3
            if ((!x.isNaN) && (!y.isNaN)) {
              new Coord(x, y)
            } else {
              closestPoint
            }
          }
        }
      case None =>
        p1
    }
  }

  /**
    * generates stall locations per a sampling technique which induces noise as a function of stall attribute availability
    * @param rand random generator used to create stall locations
    * @param agent position of agent
    * @param taz position of TAZ centroid
    * @param availabilityRatio availability of the chosen stall type, as a ratio, i.e., in the range [0, 1]
    * @return a sampled location
    */
  def availabilityAwareSampling(
    rand: Random,
    agent: Location,
    taz: TAZ,
    availabilityRatio: Double,
    closestZone: Boolean = true
  ): Location = {

    val xDistance: Double = taz.coord.getX - agent.getX
    val yDistance: Double = taz.coord.getY - agent.getY
    val euclideanDistanceToTazCenter = Math.sqrt(Math.pow(xDistance, 2.0) + Math.pow(yDistance, 2.0))
    val tazCharacteristicRadius: Double = math.sqrt(taz.areaInSquareMeters / 3.14)
    val sampleStandardDeviation: Double = tazCharacteristicRadius * 0.5

    // this coefficient models the effect of parking supply constraint on the distance a parking stall
    // might be placed from the agent's desired destination
    val exponent = -0.25 // Parameter relating distance to parking stalls to availability. More negative means that
    // for a given availability ratio parking stalls appear closer to the request rather than being
    // randomly distributed throughout taz
    val minimumAvailabilityRatio = Math.exp(1 / exponent) // Parking ratio below which we assume random availability
    // within TAZ
    val availabilityFactor: Double =
      if (availabilityRatio < minimumAvailabilityRatio) 1.0 else exponent * math.log(availabilityRatio)

    // finding a location between the agent and the TAZ centroid to sample from. If we're dealing with the closest TAZ,
    // the center of the sampling distribution should be nearer to the TAZ center the lower availability is, but nearer
    // the request if availability is high. If we're dealing with a farther-away taz, center the sampling distribution
    // halfway between the TAZ center and the nearest point in the TAZ to the request
    val (expectedValueX, expectedValueY) = if (closestZone) {
      (
        xDistance * availabilityFactor + agent.getX,
        yDistance * availabilityFactor + agent.getY
      )
    } else {
      (
        taz.coord.getX - xDistance * (tazCharacteristicRadius / euclideanDistanceToTazCenter / 2.0),
        taz.coord.getY - yDistance * (tazCharacteristicRadius / euclideanDistanceToTazCenter / 2.0)
      )
    }

    val (offsetX, offsetY) = (
      rand.nextGaussian * availabilityFactor * sampleStandardDeviation,
      rand.nextGaussian * availabilityFactor * sampleStandardDeviation
    )

    val offsetDistance = Math.sqrt(Math.pow(offsetX, 2.0) + Math.pow(offsetY, 2.0))

    // Since we could be dealing with very big zones, set a cap on the walking distance within the the closest zone,
    // even if availability is low

    val offsetMultiplier = if ((offsetDistance > maxOffsetDistance) & closestZone) {
      maxOffsetDistance / offsetDistance
    } else { 1.0 }

    // the random variable has a standard deviation made of an inverse of parking availability and scaled out
    // proportionally to 1/3 the diameter of the TAZ.
    // random value offset by the agent location and additionally offset by the distance from agent
    // to TAZ centroid with inverse availability also being a factor here.
    val (sampleX, sampleY) = (
      offsetX * offsetMultiplier + expectedValueX,
      offsetY * offsetMultiplier + expectedValueY
    )

    new Coord(sampleX, sampleY)
  }

  /**
    * samples a random location near a TAZ's centroid in order to create a stall in that TAZ.
    * previous dev's note: make these distributions more custom to the TAZ and stall type
    * @param rand random generator
    * @param center location we are sampling from
    *
    * @return a coordinate near that TAZ
    *
    * @deprecated
    */
  def sampleLocationForStall(rand: Random, center: Location, radius: Double): Location = {
    val lambda = 0.01
    val deltaRadiusX = -math.log(1 - (1 - math.exp(-lambda * radius)) * rand.nextDouble()) / lambda
    val deltaRadiusY = -math.log(1 - (1 - math.exp(-lambda * radius)) * rand.nextDouble()) / lambda

    val x = center.getX + deltaRadiusX
    val y = center.getY + deltaRadiusY
    new Location(x, y)
  }
}
