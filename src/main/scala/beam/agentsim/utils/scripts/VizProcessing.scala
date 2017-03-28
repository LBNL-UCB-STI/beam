package beam.agentsim.utils.scripts

/**
  * Created by sfeygin on 3/28/17.
  */
object VizProcessing {
  import JsonUtils._
  def main(args: Array[String]): Unit = {
    JsonUtils.processEventsFile(args(0))
  }
}
