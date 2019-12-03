package beam.side.route.processing.tract

import java.nio.file.Paths

import beam.side.route.model.CencusTrack
import beam.side.route.processing.{CencusTractDictionary, DataLoader}
import zio._

class CencusTractDictionaryIO extends CencusTractDictionary[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue] {

  override def compose(cencusTrackPath: String)(
    implicit dataLoader: DataLoader[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue]
  ): RIO[ZEnv, Promise[Throwable, Map[String, CencusTrack]]] =
    for {
      cencusQueue <- Queue.bounded[CencusTrack](256)
      promise     <- Promise.make[Throwable, Map[String, CencusTrack]]
      _ <- zio.stream.Stream
        .fromQueue[Throwable, CencusTrack](cencusQueue)
        .foldM(Map[String, CencusTrack]())((acc, ct) => IO.effectTotal(acc + (ct.id -> ct)))
        .flatMap(promise.succeed)
        .fork
      _ <- ZManaged
        .make(IO.effectTotal(cencusQueue))(q => q.shutdown)
        .use(
          queue =>
            DataLoader[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue]
              .loadData[CencusTrack](Paths.get(cencusTrackPath), queue, false)
        )
        .fork
    } yield promise

}

object CencusTractDictionaryIO {
  def apply(): CencusTractDictionaryIO = new CencusTractDictionaryIO()
}
