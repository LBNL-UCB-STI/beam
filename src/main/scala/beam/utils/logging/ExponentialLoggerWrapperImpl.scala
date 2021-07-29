package beam.utils.logging

import java.util.concurrent.ConcurrentHashMap

import beam.utils.logging.ExponentialLoggerWrapperImpl._
import org.slf4j.{Logger, LoggerFactory}

class ExponentialLoggerWrapperImpl(name: String) extends LoggerWrapper {

  private type Func = String => Unit

  private val logger: Logger = LoggerFactory.getLogger(name)

  override def error(msgTemplate: String, args: Any*): Unit = {
    if (logger.isErrorEnabled) {
      processMessage(msgTemplate) { message =>
        logger.error(message, args.map(_.asInstanceOf[AnyRef]): _*)
      }
    }
  }

  def warn(msgTemplate: String, args: Any*): Unit = {
    if (logger.isWarnEnabled) {
      processMessage(msgTemplate) { message =>
        logger.warn(message, args.map(_.asInstanceOf[AnyRef]): _*)
      }
    }
  }

  override def info(msgTemplate: String, args: Any*): Unit = {
    if (logger.isInfoEnabled) {
      processMessage(msgTemplate) { message =>
        logger.info(message, args.map(_.asInstanceOf[AnyRef]): _*)
      }
    }
  }

  override def debug(msgTemplate: String, args: Any*): Unit = {
    if (logger.isDebugEnabled) {
      processMessage(msgTemplate) { message =>
        logger.debug(message, args.map(_.asInstanceOf[AnyRef]): _*)
      }
    }
  }

  private def processMessage(messageTemplate: String)(func: Func): Unit = {
    // by focusing on first 20 characters, we allow people to avoid verbose messages
    // while keeping some unique info in the message. just put that info at the end
    val first20Characters = messageTemplate.substring(0, Math.min(19, messageTemplate.length - 1))
    val newValue = messages.merge(first20Characters, 1, (counter, incValue) => counter + incValue)
    if (isNumberPowerOfTwo(newValue)) {
      val newMessage = "[" + newValue + "] " + messageTemplate
      func(newMessage)
    }
  }

  override def reset(): Unit = messages.clear()

}

private[logging] object ExponentialLoggerWrapperImpl {

  def isNumberPowerOfTwo(number: Int): Boolean = {
    number > 0 && ((number & (number - 1)) == 0)
  }

  private val messages: ConcurrentHashMap[String, Int] = new ConcurrentHashMap[String, Int]()

}
