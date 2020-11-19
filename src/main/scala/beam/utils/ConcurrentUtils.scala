package beam.utils

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration

object ConcurrentUtils {

  def parallelExecution[A](functions: Seq[() => A])(implicit ec: ExecutionContext): Seq[A] = {
    val future = Future.sequence(functions.map(f => Future { f() }))
    Await.result(future, Duration.Inf)
  }

  // unfortunately call-by-name params don't support varargs
  def parallelExecution[A](v1: => A, v2: => A, v3: => A, v4: => A, v5: => A)(implicit ec: ExecutionContext): Seq[A] = {
    parallelExecution(List(() => v1, () => v2, () => v3, () => v4, () => v5))
  }
}
