package beam.router

import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}

trait Router {
  def calcRoute(request: RoutingRequest): RoutingResponse
}
