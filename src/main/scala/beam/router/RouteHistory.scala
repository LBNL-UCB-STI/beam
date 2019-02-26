package beam.router

import javax.inject.Inject
import probability_monad.Distribution

import scala.collection.concurrent.TrieMap

class RouteHistory @Inject()() {
  var routeHistory: TrieMap[Int, TrieMap[Int, TrieMap[Int, IndexedSeq[Int]]]] = TrieMap()
  val randNormal = Distribution.normal
  val randUnif = Distribution.uniform

  def timeToBin(departTime: Int) = {
    Math.floorMod(Math.floor(departTime.toDouble / 3600.0).toInt, 24)
  }

  def rememberRoute(route: IndexedSeq[Int], departTime: Int): Unit = {
    val timeBin = timeToBin(departTime)
    routeHistory.get(timeBin) match {
      case Some(subMap) =>
        subMap.get(route.head) match {
          case Some(subSubMap) =>
            subSubMap.put(route.last, route)
          case None =>
            subMap.put(route.head, TrieMap(route.last -> route))
        }
      case None =>
        routeHistory.put(timeBin, TrieMap(route.head -> TrieMap(route.last -> route)))
    }
  }

  def getRoute(orig: Int, dest: Int, time: Int): Option[IndexedSeq[Int]] = {
    val timeBin = timeToBin(time + (randNormal.get * 1500.0).toInt)
    routeHistory.get(timeBin) match {
      case Some(subMap) =>
        subMap.get(orig) match {
          case Some(subSubMap) =>
            subSubMap.get(dest)
          case None =>
            None
        }
      case None =>
        None
    }
  }

  def expireRoutes(fracToExpire: Double) = {
    routeHistory = TrieMap()
    val fracAtEachLevel = Math.pow(fracToExpire, 0.33333)
    routeHistory.keys.foreach { key1 =>
      if (randUnif.get < fracAtEachLevel) {
        routeHistory(key1).keys.foreach { key2 =>
          if (randUnif.get < fracAtEachLevel) {
            routeHistory(key1)(key2).keys.foreach { key3 =>
              if (randUnif.get < fracAtEachLevel) {
                routeHistory(key1)(key2).remove(key3)
              }
            }
          }
        }
      }
    }
  }
}
