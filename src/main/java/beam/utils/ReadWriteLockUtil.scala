package beam.utils

import java.util.concurrent.locks.ReadWriteLock

object ReadWriteLockUtil {

  implicit class RichReadWriteLock(val lock: ReadWriteLock) extends AnyVal {

    def read[T](op: => T): T = {
      lock.readLock().lock()
      try {
        op
      } finally {
        lock.readLock().unlock()
      }
    }

    def write[T](op: => T): T = {
      lock.writeLock().lock()
      try {
        op
      } finally {
        lock.writeLock().unlock()
      }
    }

  }
}
