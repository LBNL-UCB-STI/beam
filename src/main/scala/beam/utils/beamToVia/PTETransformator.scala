package beam.utils.beamToVia

import beam.agentsim.events.PathTraversalEvent

import scala.collection.mutable

object PTETransformator {

  def transformSingle(pte: PathTraversalEvent, vehicleId: String): Seq[PathLinkEvent] = {
    val (_, times) =
      pte.linkTravelTime.foldLeft((pte.time, mutable.MutableList.empty[(Double, Double)]))((pair, time) => {
        val (lastTime, times) = pair
        val linkTime = lastTime + time
        times += Tuple2(lastTime, linkTime)
        (linkTime, times)
      })

    val paths = pte.linkIds
      .zip(times)
      .flatMap {
        case (id, timeTuple) =>
          val (enteredTime, leftTime) = timeTuple
          val entered =
            PathLinkEvent(enteredTime, "entered link", vehicleId, id)
          val left = PathLinkEvent(leftTime, "left link", vehicleId, id)
          Seq(entered, left)
      }

    paths
  }

  def transformMultiple(
    events: Traversable[PathTraversalEvent]
  ): (Traversable[PathLinkEvent], mutable.Map[String, mutable.HashSet[String]]) = {
    val (pathLinkEvents, typeToIdSeq) = events
      .foldLeft(
        (
          mutable.MutableList[PathLinkEvent](),
          mutable
            .Map[String, mutable.HashSet[String]]()
        )
      )((accumulator, pte) => {
        val (links, typeToIdMap) = accumulator

        val vehicleType = pte.mode + "__" + pte.vehicleType
        val vehicleId = vehicleType + "__" + pte.vehicleId

        links ++= PTETransformator.transformSingle(pte, vehicleId)

        typeToIdMap.get(vehicleType) match {
          case Some(mutableSeq) => mutableSeq += vehicleId
          case _ =>
            typeToIdMap += (vehicleType -> mutable.HashSet[String](vehicleId))
        }

        (links, typeToIdMap)
      })

    (pathLinkEvents, typeToIdSeq)
  }
}
