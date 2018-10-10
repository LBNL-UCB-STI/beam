package beam.utils.logging

trait LoggerWrapper {

  def error(messageTemplate: String, args: Object*): Unit

  def warn(messageTemplate: String, args: Object*): Unit

  def info(messageTemplate: String, args: Object*): Unit

  def debug(messageTemplate: String, args: Object*): Unit

}
