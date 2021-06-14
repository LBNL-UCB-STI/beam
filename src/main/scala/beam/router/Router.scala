package beam.router

import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}

trait Router {

  def calcRoute(
    request: RoutingRequest,
    buildDirectCarRoute: Boolean = true,
    buildDirectWalkRoute: Boolean = true
  ): RoutingResponse
}
