package beam.agentsim.infrastructure.h3.generator

import scala.util.Random

import beam.agentsim.infrastructure.h3.{H3Content, H3Index, H3Wrapper}

object H3ContentGenerator {

  def build: H3Content = {
    val points = H3PointGenerator.buildSetWithFixedSize(5 + Random.nextInt(10))
    H3Content.fromPoints(points, H3Content.MinResolution)
  }


}
