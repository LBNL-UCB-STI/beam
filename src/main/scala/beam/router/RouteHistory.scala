package beam.router

import javax.inject.Inject

import scala.collection.mutable.ArrayBuffer

class RouteHistory @Inject()() {

  var routeHistory: ArrayBuffer[Int] = ArrayBuffer()

  def rememberRoute(route: ArrayBuffer[Int], iteration: Int): Unit = {
    routeHistory = routeHistory :+ iteration
  }
}
