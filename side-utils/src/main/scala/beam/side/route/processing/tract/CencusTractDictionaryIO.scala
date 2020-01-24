package beam.side.route.processing.tract

import java.nio.file.Paths

import beam.side.route.model.CencusTrack
import beam.side.route.processing.{CencusTractDictionary, DataLoader}
import zio._

class CencusTractDictionaryIO extends CencusTractDictionary[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue] {

  override def compose(cencusTrackPath: String)(
    implicit dataLoader: DataLoader[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue]
  ): RIO[ZEnv, Promise[Throwable, (Set[Int], Map[String, CencusTrack])]] =
    for {
      cencusQueue <- Queue.bounded[CencusTrack](16)
      promise     <- Promise.make[Throwable, (Set[Int], Map[String, CencusTrack])]
      _ <- zio.stream.Stream
        .fromQueue[Throwable, CencusTrack](cencusQueue)
        .fold((Set[Int](), Map[String, CencusTrack]()))(
          (acc, ct) => (acc._1 + ct.state, acc._2 + (ct.id -> ct))
        )
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
