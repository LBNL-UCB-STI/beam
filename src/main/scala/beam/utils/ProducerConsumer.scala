package beam.utils

import java.io.{PrintWriter, StringWriter}
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

class ProducerConsumer[Raw](
  produce: () => Option[Raw],
  consume: Raw => Unit,
  log: String => Unit,
  numberOfParallelTransformers: Int = 4,
  desiredInternalWorkQueueSize: Int = 1000,
  transformerProgressReportInterval: Int = 12345
)(implicit executionContext: ExecutionContext) {

  private val workQueueSize = Math.max(numberOfParallelTransformers * 10, desiredInternalWorkQueueSize)
  private val workQueue = new ArrayBlockingQueue[Option[Raw]](workQueueSize)
  private val readingFailed = new AtomicBoolean(false)

  @annotation.tailrec
  private def produceLoop(completed: Int = 0): Int = {
    if (readingFailed.get()) completed
    else
      produce() match {
        case someWork @ Some(_) =>
          workQueue.put(someWork)
          if (completed % (transformerProgressReportInterval * numberOfParallelTransformers) == 0) {
            log(s"Reader sent $completed blocks.")
          }
          produceLoop(completed + 1)

        case _ => completed
      }
  }

  @annotation.tailrec
  private def consumeLoop(consumerId: Int, completed: Int = 1): Int =
    if (readingFailed.get()) completed
    else
      workQueue.take() match {
        case Some(work) =>
          consume(work)

          if (completed % transformerProgressReportInterval == 0) {
            log(s"Parallel transformer#$consumerId completed $completed blocks.")
          }

          consumeLoop(consumerId, completed + 1)

        case _ => completed
      }

  private def cleanQueueStopWorkers: PartialFunction[Throwable, Unit] = { case exception: Exception =>
    val sw = new StringWriter()
    exception.printStackTrace(new PrintWriter(sw))
    log(s"Exception during reading. Exception: ${exception.toString}, ${sw.toString}")

    readingFailed.set(true)
    workQueue.clear()
    (1 to numberOfParallelTransformers).foreach(_ => workQueue.offer(None))

    throw exception
  }

  def waitForTransformationToComplete(atMost: Duration = Duration.Inf): Future[Seq[Unit]] = {
    Await.ready(readAndTransformInParallel(), atMost)
  }

  def readAndTransformInParallel(): Future[Seq[Unit]] = {

    val dataReader: Future[Unit] = Future {
      log(s"Map Reader started, number of workers: $numberOfParallelTransformers, internal queue size $workQueueSize")
      val numberOfWorkDid = produceLoop()
      log(s"Map Reader finished. Number of read blocks: $numberOfWorkDid.")
      (1 to numberOfParallelTransformers).foreach(_ => workQueue.put(None))
    }

    val consumers: Seq[Future[Unit]] = (1 to numberOfParallelTransformers).map(consumerId =>
      Future {
        log(s"Parallel transformer#$consumerId started.")
        val transformationsDone = consumeLoop(consumerId)
        log(s"Parallel transformer#$consumerId finished, $transformationsDone blocks completed.")
      }
    )

    val futures = (consumers :+ dataReader).map(_.recover(cleanQueueStopWorkers))
    val future: Future[Seq[Unit]] = Future.sequence(futures)
    future
  }
}
