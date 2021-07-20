package beam.utils

import java.io.Closeable

object CloseableUtil {

  implicit class RichCloseable[T <: Closeable](val closeable: T) extends AnyVal {

    def use[V](op: T => V): V =
      try {
        op(closeable)
      } finally {
        closeable.close()
      }

  }
}
