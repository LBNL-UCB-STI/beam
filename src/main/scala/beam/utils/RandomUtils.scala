package beam.utils
import java.util.Random

object RandomUtils {

  def shuffle[T](it: Iterable[T], rnd: Random): Iterable[T] = {
    new scala.util.Random(rnd).shuffle(it)
  }
}
