package beam.router

import beam.router.BeamRouter.{EmbodyWithCurrentTravelTime, RoutingRequest, RoutingResponse}

trait Router {

  def calcRoute(
    request: RoutingRequest,
    buildDirectCarRoute: Boolean = true,
    buildDirectWalkRoute: Boolean = true
  ): RoutingResponse

  def embodyWithCurrentTravelTime(request: EmbodyWithCurrentTravelTime): RoutingResponse = ???

}
