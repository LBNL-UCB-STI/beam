package beam.side.route.processing.tract

import java.nio.file.Paths

import beam.side.route.model.{CencusTrack, GHPaths, Trip, TripPath}
import beam.side.route.processing.{DataLoader, GHRequest, ODCompute, PathCompute}
import org.http4s.EntityDecoder
import zio._

class ODComputeIO(parallel: Int, factor: Int) extends ODCompute[({ type T[A] = RIO[zio.ZEnv, A] })#T] {

  import zio.console._

  def pairTrip(odPairsPath: Option[String], tracts: Map[String, CencusTrack])(
    implicit pathCompute: PathCompute[({ type T[A] = RIO[zio.ZEnv, A] })#T],
    pathEncoder: EntityDecoder[({ type T[A] = RIO[zio.ZEnv, A] })#T, GHPaths],
    ghRequest: GHRequest[({ type T[A] = RIO[zio.ZEnv, A] })#T],
    dataLoader: DataLoader[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue]
  ): RIO[ZEnv, Queue[TripPath]] =
    for {
      tripQueue <- Queue.bounded[Trip](parallel * factor)
      pathQueue <- Queue.bounded[TripPath](parallel * parallel * factor / 2)
      _ <- zio.stream.Stream
        .fromQueue[Throwable, Trip](tripQueue)
        .zipWithIndex
        .mapMParUnordered(parallel) {
          case (trip, idx) =>
            putStrLn((idx -> trip).toString) &> PathCompute[({ type T[A] = RIO[zio.ZEnv, A] })#T]
              .compute(trip, tracts, pathQueue)
        }
        .runDrain
        .fork
      _ <- ZManaged
        .make(IO.effectTotal(tripQueue))(q => q.shutdown)
        .zip(ZManaged.make(IO.effectTotal(pathQueue))(q => q.shutdown))
        .zip(ZManaged.fromEffect(IO.fromOption(odPairsPath)))
        .use {
          case ((queue, _), path) =>
            DataLoader[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue]
              .loadData[Trip](Paths.get(path), queue, false)
        }
        .fork
    } yield pathQueue

}

object ODComputeIO {
  def apply(parallel: Int, factor: Int): ODComputeIO = new ODComputeIO(parallel, factor)
}
