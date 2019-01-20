package beam.utils
import java.util.concurrent.atomic.AtomicInteger

trait IdGenerator {
  def nextId: Int
}

object IdGeneratorImpl extends IdGenerator {
  private val id: AtomicInteger = new AtomicInteger(0)

  def nextId: Int = {
    id.getAndIncrement()
  }
}
