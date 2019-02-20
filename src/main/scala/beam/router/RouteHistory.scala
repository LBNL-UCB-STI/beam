package beam.router

import javax.inject.Inject

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ArrayBuffer

class RouteHistory @Inject()() {


  var routeHistory: TrieMap[Int,TrieMap[Int,TrieMap[Int,IndexedSeq[Int]]]] = TrieMap()

  def timeToBin(departTime: Int) = {
    Math.floorMod(Math.floor(departTime.toDouble / 3600.0).toInt,24)
  }

  def rememberRoute(route: IndexedSeq[Int], departTime: Int): Unit = {
    val timeBin = timeToBin(departTime)
    routeHistory.get(timeBin) match {
      case Some(subMap) =>
        subMap.get(route.head) match {
          case Some(subSubMap) =>
            subSubMap.put(route.last,route)
          case None =>
            subMap.put(route.head,TrieMap(route.last->route))
        }
      case None =>
        routeHistory.put(timeBin,TrieMap(route.head -> TrieMap(route.last -> route)))
    }
    val i = 0
  }

  def getRoute(orig: Int, dest: Int, time: Int): Option[IndexedSeq[Int]] = {
    val timeBin = timeToBin(time)
    routeHistory.get(timeBin) match{
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
}
