package beam.utils

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class ProducerConsumerTest extends AnyWordSpec with Matchers with ScalaFutures {

  implicit val defaultPatience: PatienceConfig = PatienceConfig(timeout = 2.seconds, interval = 200.millis)

  "ProducerConsumer as parallel reader" should {

    "work in an optimistic scenario (happy path)" in {
      val data = List("a" -> 1, "b" -> 2, "c" -> 3)
      val iterator = data.iterator

      val trieMap = scala.collection.concurrent.TrieMap.empty[String, Int]

      val reader = new ProducerConsumer[(String, Int)](
        produce = () => if (iterator.hasNext) Some(iterator.next()) else None,
        consume = raw => trieMap.put(raw._1, raw._2),
        log = println,
        numberOfParallelTransformers = 2
      )

      reader.waitForTransformationToComplete()
      trieMap should have size 3
      trieMap("a") shouldBe 1
    }

    "terminate and fail the future if a consumer throws an exception" in {
      val reader = new ProducerConsumer[Int](
        produce = () => Some(scala.util.Random.nextInt()),
        consume = _ => throw new RuntimeException("Consumer Boom!"),
        log = println,
        numberOfParallelTransformers = 2
      )

      val result = reader.readAndTransformInParallel()

      whenReady(result.failed) { ex =>
        ex shouldBe a[RuntimeException]
        ex.getMessage shouldBe "Consumer Boom!"
      }
    }

    "terminate and fail the future if the producer throws an exception" in {
      val reader = new ProducerConsumer[Int](
        produce = () => throw new RuntimeException("Producer Boom!"),
        consume = i => (i, i),
        log = println,
        numberOfParallelTransformers = 2
      )

      val result = reader.readAndTransformInParallel()

      whenReady(result.failed) { ex =>
        ex shouldBe a[RuntimeException]
        ex.getMessage shouldBe "Producer Boom!"
      }
    }

    "handle an empty data source gracefully" in {
      val trieMap = scala.collection.concurrent.TrieMap.empty[Int, Int]

      val reader = new ProducerConsumer[Int](
        produce = () => None,
        consume = i => trieMap.put(i, i),
        log = println
      )

      reader.waitForTransformationToComplete()
      trieMap should be(empty)
    }

    "handle data volume exceeding the internal queue size" in {
      val limit = 2000
      val counter = new AtomicInteger(0)
      val trieMap = scala.collection.concurrent.TrieMap.empty[Int, Int]

      val reader = new ProducerConsumer[Int](
        produce = () => {
          val c = counter.getAndIncrement()
          if (c < limit) Some(c) else None
        },
        consume = i => trieMap.put(i, i),
        log = _ => (),
        desiredInternalWorkQueueSize = 2 // Smaller than limit to force blocking
      )

      reader.waitForTransformationToComplete()
      trieMap should have size limit
    }
  }
}
