package beam.router

import com.typesafe.scalalogging.LazyLogging
import javax.inject.Inject
import probability_monad.Distribution

import scala.collection.concurrent.TrieMap

class RouteHistory @Inject()() extends LazyLogging {
  private var routeHistory: TrieMap[Int, TrieMap[Int, TrieMap[Int, IndexedSeq[Int]]]] = TrieMap()
  private val randNormal = Distribution.normal
  private val randUnif = Distribution.uniform
  @volatile private var cacheRequests = 0
  @volatile private var cacheHits = 0

  private def timeToBin(departTime: Int) = {
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
    cacheRequests = cacheRequests + 1
//    val timeBin = timeToBin(time + (randNormal.get * 1500.0).toInt)
    val timeBin = timeToBin(time)
    routeHistory.get(timeBin) match {
      case Some(subMap) =>
        subMap.get(orig) match {
          case Some(subSubMap) =>
            cacheHits = cacheHits + 1
            subSubMap.get(dest)
          case None =>
            None
        }
      case None =>
        None
    }
  }

  def expireRoutes(fracToExpire: Double) = {
    logger.info(
      "Overall cache hits {}/{} ({}%)",
      cacheHits,
      cacheRequests,
      Math.round(cacheHits.toDouble / cacheRequests.toDouble * 100)
    )
    cacheRequests = 0
    cacheHits = 0
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
